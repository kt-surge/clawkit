package com.clawkit.cli.ops;

import com.clawkit.ops.delivery.managed.ManagedOperationsService;
import com.clawkit.ops.mcp.*;
import com.clawkit.provider.*;
import com.clawkit.tools.schema.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import static org.assertj.core.api.Assertions.*;

/** Real CLI -> facade -> AgentEngine -> tool executor -> bounded adapters; model and Docker are controlled doubles. */
class AutonomyDiagnosisProductTest {
    @TempDir Path root;
    static final ObjectMapper JSON=new ObjectMapper();
    static final Instant NOW=Instant.parse("2026-09-30T10:00:00Z");
    static final String API="a".repeat(64),DB="d".repeat(64),PROJECT="clawkit-autonomy-diagnosis-test";

    @ParameterizedTest @ValueSource(strings={"dependency","configuration","oom","historical","truncated"})
    void productEntryExplainsFourChainsAndTruncatedLogsCannotCauseARepair(String scenario) throws Exception {
        Path compose=root.resolve("compose.yaml"); Files.writeString(compose,"services: {api: {}, db: {}}"); compose=compose.toRealPath();
        var writes=new AtomicInteger(); final Path pinnedFile=compose;
        CommandExecutor docker=(args,env,timeout,cap) -> {
            assertThat(args.subList(0,3)).containsExactly("docker","--context","default");
            String command=args.get(3);
            switch(command) {
                case "context": return ok("unix:///var/run/docker.sock");
                case "info": return ok(args.get(5).equals("{{.OSType}}") ? "linux" : "daemon-test");
                case "ps": return ok(args.toString().contains("service=db") ? DB : API);
                case "inspect": {
                    if (args.get(5).contains("OOMKilled")) return ok((scenario.equals("oom") ? "true 137" : "false 0")+" \"2026-09-30T09:59:50Z\" 134217728");
                    boolean dependency=args.getLast().equals(DB);
                    var labels=JSON.createObjectNode().put("com.docker.compose.project",PROJECT).put("com.docker.compose.service",dependency ? "db" : "api")
                        .put("com.docker.compose.project.config_files",pinnedFile.toString());
                    String state=!dependency && scenario.equals("oom") ? "exited" : "running";
                    return ok("\""+(dependency ? DB : API)+"\" "+labels+" \""+state+"\" false \"no\" [] \""
                        +(dependency && scenario.equals("dependency") ? "unhealthy" : "healthy")+"\"");
                }
                case "stats": return ok("\"64MiB / 128MiB\"");
                case "logs": {
                    assertThat(args).contains("--since","--until","--tail","200","--timestamps").doesNotContain("--follow");
                    assertThat(args.getLast()).isEqualTo(API); assertThat(cap).isEqualTo(8192);
                    String text="2026-09-30T09:59:20Z ERROR "+switch(scenario) {
                        case "dependency" -> "database connection refused"; case "configuration" -> "expected schema v2 but found schema v1";
                        case "oom" -> "allocation failed"; default -> "old request failed; later requests succeeded";
                    }+" password=synthetic-secret-value";
                    return new CommandResult(0,text,"",false,scenario.equals("truncated"),text.length());
                }
                default: writes.incrementAndGet(); throw new AssertionError("read-only diagnosis issued "+command);
            }
        };
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/",exchange -> {
            byte[] body="accepted".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(scenario.equals("historical") ? 200 : 500,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        }); http.start();
        try {
            var service=new ManagedOperationsService(root.resolve("state"),docker,Clock.fixed(NOW,ZoneOffset.UTC));
            var base=URI.create("http://127.0.0.1:"+http.getAddress().getPort());
            service.register(new ManagedOperationsService.RegistrationRequest("demo",compose,"default",PROJECT,"api",List.of("db"),true,
                base.resolve("/health"),base.resolve("/"),"accepted",Duration.ofSeconds(5)));
            var model=new ScriptedDiagnosis(scenario);
            var command=new AutonomyCommand(path -> service,(svc,id,m,url,p) -> svc.diagnose(id,model));
            if (scenario.equals("configuration")) {
                Path input=root.resolve("change.json"); Files.writeString(input,"""
                    {"id":"release-2","applicationId":"demo","applicationVersion":1,"environment":"clawkit-autonomy-diagnosis-test",
                    "service":"api","occurredAt":"2026-09-30T09:59:00Z","configurationVersion":"v2",
                    "summary":"Changed expected schema to v2","operator":"ci-double"}
                    """);
                assertThat(execute(command,"change-import","demo","--input",input.toString()).code()).isZero();
            }
            var result=execute(command,"diagnose","demo","--details");
            assertThat(result.code()).withFailMessage(result.out()+result.error()).isZero();
            assertThat(model.calls).hasValue(3); assertThat(writes).hasValue(0);
            assertThat(result.out()).contains("支持证据","诊断记录").doesNotContain("synthetic-secret-value","已独立验证恢复");
            var view=service.diagnosis("demo"); assertThat(view.origin()).isEqualTo("MODEL"); assertThat(view.failureType()).isNull();
            assertThat(view.hypotheses()).singleElement().satisfies(h -> assertThat(h.cause()).isEqualTo(model.cause()));
            assertThat(view.decision()).isEqualTo(scenario.equals("historical") ? "WAIT" : "ESCALATE");
            assertThat(Files.readString(view.artifact())).doesNotContain("providerExchanges","synthetic-secret-value");
            assertThat(execute(command,"diagnosis","demo").out()).contains("调查结论");
            assertThat(model.calls).hasValue(3); // Inspect does not call the model again.
            if(scenario.equals("dependency")) {
                var continuousModel=new ScriptedDiagnosis(scenario);
                try(var session=service.open("demo",continuousModel,e -> {})) { session.once(); }
                assertThat(service.status("demo").state()).isEqualTo("HANDOFF");
                var drafts=service.knowledge("demo").cases();
                assertThat(drafts).singleElement().satisfies(c -> {
                    assertThat(c.state()).isEqualTo(com.clawkit.ops.loop.managed.OpsKnowledge.State.DRAFT);
                    assertThat(c.opsCase().outcome()).isEqualTo(com.clawkit.ops.loop.managed.OpsKnowledge.OutcomeKind.HUMAN_HANDOFF);
                    assertThat(c.opsCase().proposedDiagnosis()).isNotNull(); assertThat(c.review()).isNull();
                });
                String caseId=drafts.getFirst().opsCase().id();
                assertThat(execute(command,"case-review","demo",caseId,"--confirm-reviewed","--cause","DEPENDENCY_FAILURE","--review-note","reviewed controlled fixture").code()).isZero();
                assertThat(execute(command,"knowledge-search","demo","--query","DEPENDENCY_FAILURE").out()).contains(caseId);
                assertThat(continuousModel.calls).hasValue(3); assertThat(writes).hasValue(0);
                assertThat(service.status("demo").permission()).isEqualTo("ASK");
            }
        } finally { http.stop(0); }
    }
    private record Result(int code,String out,String error) {}
    private static Result execute(AutonomyCommand command,String... args) {
        var out=new StringWriter(); var error=new StringWriter();
        int code=new CommandLine(command).setOut(new PrintWriter(out)).setErr(new PrintWriter(error)).execute(args);
        return new Result(code,out.toString(),error.toString());
    }
    private static CommandResult ok(String text) { return new CommandResult(0,text,"",false,false,text.length()); }

    static final class ScriptedDiagnosis implements LLMProvider {
        final AtomicInteger calls=new AtomicInteger(); final String scenario;
        final Map<String,String> refs=new HashMap<>();
        ScriptedDiagnosis(String scenario) { this.scenario=scenario; }
        String cause() { return switch(scenario) { case "dependency" -> "DEPENDENCY_FAILURE"; case "configuration" -> "CONFIGURATION_MISMATCH";
            case "oom" -> "RESOURCE_EXHAUSTION"; default -> "UNKNOWN"; }; }
        public ModelResponse generate(ModelRequest request) {
            assertThat(request.parameters().reasoningMode()).isEqualTo(ProviderReasoningMode.DISABLED);
            int call=calls.incrementAndGet();
            List<ToolCall> tools;
            if (call==1) tools=List.of("service","health","business","dependencies","logs","resources","changes","metrics").stream()
                .map(p -> new ToolCall("read-"+p,"mcp__remote_managed_ops__read_"+p,JSON.createObjectNode())).toList();
            else {
                for (var message:request.messages()) if (message.role()==Role.TOOL) {
                    try { var fact=JSON.readTree(message.content()); if (fact.has("observation")) {
                        refs.put(fact.path("observation").path("probe").asText(),fact.path("id").asText());
                        assertThat(fact.has("envelope")).isFalse(); assertThat(fact.path("contentHash").asText()).hasSize(64);
                    } }
                    catch (Exception ignored) {}
                }
                if (call==2) {
                    var report=JSON.createObjectNode().put("summary","根据当前事实调查原因；历史日志不等于当前故障。");
                    var h=report.putArray("hypotheses").addObject().put("id","h-primary").put("cause",cause())
                        .put("assessment",cause().equals("UNKNOWN") ? "UNKNOWN" : "SUPPORTED")
                        .put("explanation",scenario.equals("historical") ? "当前业务已正常，旧错误不足以支持重启。" : "需要人工核对具体原因，重启不消除持续的上游、配置或资源问题。");
                    var support=h.putArray("supportRefs");
                    for (String probe:switch(scenario) { case "dependency" -> List.of("DEPENDENCIES","BUSINESS","LOGS");
                        case "configuration" -> List.of("CHANGES","LOGS","BUSINESS"); case "oom" -> List.of("RESOURCES","SERVICE","BUSINESS");
                        case "historical" -> List.of("BUSINESS","HEALTH"); default -> List.of("BUSINESS"); }) support.add(refs.get(probe));
                    var counter=h.putArray("counterRefs"); if (scenario.equals("historical")) counter.add(refs.get("LOGS"));
                    h.putArray("missingEvidence").add(scenario.equals("truncated") ? "日志被截断；需要完整证据" : "指标未配置；没有历史内存趋势");
                    h.putArray("alternativeIds"); h.putArray("nextProbes");
                    tools=List.of(new ToolCall("diagnosis","mcp__remote_managed_ops__submit_diagnosis",report));
                } else {
                    var decision=JSON.createObjectNode().put("disposition",scenario.equals("historical") ? "WAIT" : "ESCALATE")
                        .put("reason","产品入口合同验证，未执行修复");
                    decision.putArray("evidenceRefs").add(refs.get("BUSINESS")); decision.putNull("playbook"); decision.putArray("nextProbes");
                    if (scenario.equals("historical")) decision.put("recheckAfterSeconds",5); else decision.putNull("recheckAfterSeconds");
                    tools=List.of(new ToolCall("decision","mcp__remote_managed_ops__submit_decision",decision));
                }
            }
            return new ModelResponse(null,tools,FinishReason.TOOL_CALLS,new TokenUsage(100,50,150),ProviderResponseMetadata.EMPTY);
        }
        public Message generate(List<Message> messages,List<ToolDefinition> tools) { throw new AssertionError("typed provider required"); }
    }
}
