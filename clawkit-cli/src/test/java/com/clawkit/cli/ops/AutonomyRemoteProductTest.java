package com.clawkit.cli.ops;

import com.clawkit.ops.delivery.managed.ManagedOperationsService;
import com.clawkit.ops.loop.OpsReadSession;
import com.clawkit.ops.loop.managed.*;
import com.clawkit.tools.mcp.McpCallResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import static org.assertj.core.api.Assertions.*;

/** Product entry uses the real Agent runtime/controller; SSH and model responses are controlled contracts. */
class AutonomyRemoteProductTest {
    @TempDir Path root;
    static final Instant NOW=AutonomyDiagnosisProductTest.NOW;
    static final ObjectMapper JSON=new ObjectMapper();
    static final String CONTAINER="c".repeat(12);
    final List<ReadSession> sessions=new ArrayList<>();
    final AtomicInteger dockerCalls=new AtomicInteger();
    ManagedOperationsService service() throws Exception {
        var remote=new ManagedOperationsService.RemoteSources() {
            public RemoteManagedSource resolve(RemoteObservationBinding b) { return new RemoteManagedSource(b,"a".repeat(64),"b".repeat(64)); }
            public OpsReadSession open(RemoteManagedSource source) { var session=new ReadSession(); sessions.add(session); return session; }
        };
        return new ManagedOperationsService(root.resolve("state"),(args,env,time,cap) -> {
            dockerCalls.incrementAndGet(); throw new AssertionError("remote source must not use local Docker");
        },Clock.fixed(NOW,ZoneOffset.UTC),remote);
    }
    @Test void remoteCommandDiagnosesAndRunsThroughExistingControllerWithoutAnyRepair() throws Exception {
        var service=service(); var model=new AutonomyDiagnosisProductTest.ScriptedDiagnosis("truncated");
        var command=new AutonomyCommand(path -> service,(svc,id,m,u,p) -> svc.diagnose(id,model));
        assertThat(execute(command,"remote-register","orders","--target","remote-test","--environment","remote-fixture","--service","order-api",
            "--container-id",CONTAINER,"--health-endpoint","orders-health","--health","http://127.0.0.1:18080/health",
            "--business-endpoint","orders-business","--business","http://127.0.0.1:18080/orders","--marker","accepted").code()).isZero();
        assertThat(execute(command,"check","orders").code()).isZero();
        var diagnosis=execute(command,"diagnose","orders","--details");
        assertThat(diagnosis.code()).withFailMessage(diagnosis.output()+diagnosis.error()).isZero();
        assertThat(diagnosis.output()).contains("诊断记录","TRUNCATED").doesNotContain("已独立验证恢复");
        assertThat(service.diagnosis("orders").evidence()).anyMatch(e -> e.source().equals("SSH_MCP"));
        var controlModel=new AutonomyDiagnosisProductTest.ScriptedDiagnosis("truncated");
        try(var session=service.open("orders",controlModel,event -> {})) {
            session.once(); session.once();
            var status=service.status("orders");
            assertThat(status.state()).isEqualTo("HANDOFF"); assertThat(status.permission()).isEqualTo("OBSERVE");
            assertThat(status.decisions()).isEqualTo(1); assertThat(status.observations()).isEqualTo(2);
            assertThat(service.events("orders",100).stream().filter(e -> e.kind().equals("INCIDENT_CREATED")).count()).isEqualTo(1);
            assertThat(controlModel.calls).hasValue(3);
            assertThat(execute(command,"pause","orders").code()).isZero();
            assertThat(service.status("orders").requestedMode()).isEqualTo("PAUSED");
            assertThat(execute(command,"resume","orders").code()).isZero();
            assertThat(service.status("orders").requestedMode()).isEqualTo("RUNNING");
        }
        assertThat(dockerCalls).hasValue(0);
        assertThat(sessions).allMatch(s -> s.closed);
        assertThat(sessions.stream().flatMap(s -> s.calls.stream())).allMatch(Set.of("container_status","container_resources","http_probe","logs")::contains);
    }
    @Test void missingRemotePredicatesRemainGapsAndPolicyApprovalCannotPromoteLegacyOpsfix() throws Exception {
        var service=service(); var command=new AutonomyCommand(path -> service);
        assertThat(execute(command,"remote-register","orders","--target","remote-test","--environment","remote-fixture","--service","order-api","--container-id",CONTAINER).code()).isZero();
        int connected=sessions.size();
        assertThat(execute(command,"policy","orders","ask").code()).isEqualTo(2);
        assertThat(execute(command,"policy","orders","limited-auto","--confirm-reviewed","--review-note","fixture reviewed").code()).isEqualTo(2);
        assertThat(sessions).hasSize(connected); assertThat(dockerCalls).hasValue(0);
        var registration=new ManagedRegistrationStore(root.resolve("state"),Clock.fixed(NOW,ZoneOffset.UTC)).read("orders");
        assertThat(registration.application().healthUri()).isNull(); assertThat(registration.application().businessUri()).isNull();
        try(var observer=new RemoteManagedObserver(registration.remote().binding(),new ReadSession(),Clock.fixed(NOW,ZoneOffset.UTC),null)) {
            assertThat(observer.observe(registration.application(),ManagedObserver.Probe.BUSINESS).payload().collection().quality()).isEqualTo(EvidenceEnvelope.Quality.MISSING);
        }
    }
    @Test void wrongPinnedContainerFailsBeforeRegistrationOrLocalCommands() throws Exception {
        var service=service(); var command=new AutonomyCommand(path -> service);
        assertThat(execute(command,"remote-register","orders","--target","remote-test","--environment","remote-fixture","--service","order-api","--container-id","d".repeat(12)).code()).isEqualTo(2);
        assertThat(service.applications()).isEmpty(); assertThat(dockerCalls).hasValue(0); assertThat(sessions).allMatch(s -> s.closed);
    }
    private record Result(int code,String output,String error) {}
    private static Result execute(AutonomyCommand command,String... args) {
        var out=new StringWriter(); var err=new StringWriter();
        int code=new CommandLine(command).setOut(new PrintWriter(out)).setErr(new PrintWriter(err)).execute(args);
        return new Result(code,out.toString(),err.toString());
    }
    static final class ReadSession implements OpsReadSession {
        boolean closed; final List<String> calls=new ArrayList<>();
        public String targetId() { return "remote-test"; }
        public boolean isReady() { return !closed; }
        public void close() { closed=true; }
        public McpCallResult callTool(String tool,ObjectNode arguments) throws IOException {
            calls.add(tool); var data=JSON.createObjectNode(); String target="order-api";
            switch(tool) {
                case "container_status" -> {
                    data.put("service",target).put("containerId",CONTAINER);
                    data.putObject("state").put("Running",true).put("Restarting",false).put("OOMKilled",false).put("ExitCode",0);
                }
                case "container_resources" -> data.put("service",target).put("containerId",CONTAINER).put("MemUsage","32MiB / 128MiB");
                case "http_probe" -> {
                    target=arguments.path("endpoint").asText();
                    data.put("endpoint",target).put("uri","http://127.0.0.1:18080/"+(target.endsWith("health") ? "health" : "orders"))
                        .put("statusCode",500).put("body","temporarily unavailable");
                }
                case "logs" -> data.put("service",target).put("containerId",CONTAINER).put("since",NOW.minusSeconds(300).toString())
                    .put("until",NOW.toString()).put("text","request failed; partial logs");
                default -> throw new AssertionError("unexpected remote tool "+tool);
            }
            var frame=JSON.createObjectNode().put("tool",tool).put("target",target).put("observedAt",NOW.toString())
                .put("collectedAt",NOW.toString()).put("current",true).put("success",true);
            frame.set("data",data); frame.putObject("audit").put("truncated",tool.equals("logs"));
            return McpCallResult.success(frame.toString(),List.of());
        }
    }
}
