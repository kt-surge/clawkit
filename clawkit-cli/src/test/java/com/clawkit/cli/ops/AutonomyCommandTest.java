package com.clawkit.cli.ops;

import com.clawkit.cli.ClawkitApp;
import com.clawkit.ops.loop.managed.*;
import com.clawkit.ops.delivery.managed.ManagedOperationsService;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import static org.assertj.core.api.Assertions.*;

class AutonomyCommandTest {
    @TempDir Path root;
    @Test void registeredTopLevelHelpAndAutonomyHelpDoNotBootstrapTheGeneralAgent() {
        var text=new StringWriter(); var command=new CommandLine(new ClawkitApp()).setOut(new PrintWriter(text));
        assertThat(command.execute("autonomy","--help")).isZero();
        assertThat(text.toString()).contains("register","--confirm-reviewed","--chat-id");
        var isolated=new CommandLine(new AutonomyCommand(path -> { throw new AssertionError("help must not construct services"); })).setOut(new PrintWriter(new StringWriter()));
        assertThat(isolated.execute("--help")).isZero();
    }
    @Test void statusAndPauseUseOnlyLocalConfigurationAndDoNotNeedDockerOrAModel() throws Exception {
        registerLocalConfig(); var result=execute("status","demo");
        assertThat(result.code).isZero(); assertThat(result.output).contains("clawkit-autonomy-cli-test/orders","需人工审批","控制进程：未运行");
        assertThat(execute("pause","demo").code).isZero();
        assertThat(execute("status","demo").output).contains("已暂停");
    }
    @Test void approvalAndAutomaticPermissionRequireAnExplicitUserDecision() throws Exception {
        registerLocalConfig();
        var approval=execute("approve","demo","inc-test"); assertThat(approval.code).isEqualTo(2); assertThat(approval.error).contains("--confirm");
        var permission=execute("policy","demo","limited-auto"); assertThat(permission.code).isEqualTo(2); assertThat(permission.error).contains("--confirm-reviewed");
        assertThat(execute("policy","demo","LIMITED-AUTO","--review-note","scope note without confirmation").code).isEqualTo(2);
        assertThat(execute("status","demo").output).contains("需人工审批");
        var missingRecipient=execute("run","demo","--notify"); assertThat(missingRecipient.code).isEqualTo(2); assertThat(missingRecipient.error).contains("--chat-id");
    }
    @Test void unknownExecutionRenderingCannotAppearAsARecoveredService() {
        var text=new StringWriter();
        AutonomyCommand.render(new ManagedOperationsService.StatusView("demo","project/orders","RUNNING","PAUSED","ASK","QUALIFIED",
            Instant.now(),"inc-unknown","HANDOFF","PROPOSE_ACTION","restart","bounded reason","OUTCOME_UNKNOWN","unknown execution",
            Instant.now(),2,1,root,false,java.util.Map.of("SERVICE","RUNNING","BUSINESS","UNKNOWN")),new PrintWriter(text));
        assertThat(text.toString()).contains("待人工处理","结果未知，禁止重复派发","自动处理已降级").doesNotContain("已独立验证恢复");
        assertThat(text.toString()).contains("服务运行中","业务检查状态未知").doesNotContain("bounded reason");
    }
    @Test void knowledgeCommandsDoNotBootstrapModelOrExpandPermissionAndRequireNamedReview() throws Exception {
        registerLocalConfig();
        assertThat(execute("knowledge-list","demo").code).isZero();
        assertThat(execute("knowledge-search","demo","--query","stopped service").output).contains("没有符合环境","不增加授权");
        var review=execute("knowledge-review","demo","missing@1","--review-note","review");
        assertThat(review.code).isEqualTo(2);
        var caseReview=execute("case-review","demo","case-missing","--review-note","review");
        assertThat(caseReview.error).contains("--confirm-reviewed","--cause");
        assertThat(execute("status","demo").output).contains("需人工审批");
        assertThat(execute("postmortem","demo").code).isEqualTo(2);
    }
    @Test void importReplayReviewSearchAndRevokeWorkThroughTheProductCommand() throws Exception {
        registerLocalConfig(); Path registration=root.resolve("demo/registration.json"); String permissionBefore=Files.readString(registration);
        var app=ManagedKnowledgeStore.read(registration,ManagedRegistrationStore.Registration.class,65536).application();
        var now=Instant.now().minusSeconds(1);
        var probe=ManagedObserver.Probe.DEPENDENCIES;
        var unhealthy=ManagedObserver.Status.UNHEALTHY;
        var book=new OpsKnowledge.RunbookVersion("dependency-guide",1,OpsKnowledge.Scope.of(app),
            "Dependency outage","dependency unavailable",new OpsKnowledge.Conditions(
                java.util.List.of(new OpsKnowledge.ProbeCondition(probe,unhealthy)),java.util.List.of(probe),null,true),
            java.util.List.of("Current dependency unhealthy"),java.util.List.of("Healthy dependency or stale facts"),java.util.List.of(probe),
            OpsDecision.Disposition.ESCALATE,null,java.util.List.of("Human dependency recovery then independent business check"),java.util.List.of(),now);
        var json=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        Path body=root.resolve("book.json"); json.writeValue(body.toFile(),book);
        assertThat(execute("knowledge-import","demo","--input",body.toString()).code).isZero();
        assertThat(execute("knowledge-list","demo").output).contains("DRAFT");
        java.util.function.Function<ManagedObserver.Status,DecisionEvidence> fact=status -> {
            String id="ev-"+status.name(); var observation=new ManagedObserver.Observation(app.targetId(),app.composeProject(),app.service(),probe,now,status,"fixture",
                EvidenceEnvelope.Payload.snapshot(EvidenceEnvelope.Source.DOCKER_INSPECT,
                    EvidenceEnvelope.Quality.COMPLETE,now,"fixture"));
            return new DecisionEvidence(id,app.id(),app.version(),observation,now.plus(app.evidenceTtl()),
                new EvidenceEnvelope(id,app.id(),app.version(),app.composeProject(),observation,ManagedKnowledgeStore.contentHash(observation)));
        };
        var samples=new ManagedKnowledgeStore.ReplayInput("cli-fixture-v1",java.util.List.of(
            new OpsKnowledge.ReplaySample("positive",app,now,java.util.List.of(fact.apply(unhealthy)),true),
            new OpsKnowledge.ReplaySample("negative",app,now,java.util.List.of(fact.apply(ManagedObserver.Status.HEALTHY)),false)));
        Path input=root.resolve("samples.json"); json.writeValue(input.toFile(),samples);
        var replay=execute("knowledge-replay","demo","dependency-guide@1","--input",input.toString()); assertThat(replay.code).isZero();
        var matcher=java.util.regex.Pattern.compile("replay-[a-f0-9-]{36}").matcher(replay.output); assertThat(matcher.find()).isTrue(); String replayId=matcher.group();
        assertThat(execute("knowledge-review","demo","dependency-guide@1","--review-note","review","--replay-id",replayId).error).contains("--confirm-reviewed");
        assertThat(execute("knowledge-review","demo","dependency-guide@1","--review-note","review","--replay-id",replayId,"--confirm-reviewed").code).isZero();
        assertThat(execute("knowledge-search","demo","--query","dependency unavailable").output).contains("dependency-guide@1","不符合/缺证");
        assertThat(Files.readString(registration)).isEqualTo(permissionBefore);
        assertThat(execute("knowledge-revoke","demo","dependency-guide@1","--review-note","withdrawn").code).isZero();
        assertThat(execute("knowledge-search","demo","--query","dependency unavailable").output).contains("没有符合环境");
        assertThat(Files.readString(registration)).isEqualTo(permissionBefore);
    }
    private Result execute(String... args) {
        var out=new StringWriter(); var error=new StringWriter();
        var command=new CommandLine(new AutonomyCommand()).setOut(new PrintWriter(out)).setErr(new PrintWriter(error));
        var parameters=new java.util.ArrayList<String>(java.util.Arrays.asList(args)); parameters.add("--state-dir"); parameters.add(root.toString());
        return new Result(command.execute(parameters.toArray(String[]::new)),out.toString(),error.toString());
    }
    private void registerLocalConfig() throws Exception {
        Path directory=Files.createDirectories(root.resolve("demo"));
        Files.writeString(directory.resolve("registration.json"),"""
            {"application":{"id":"demo","targetId":"local-isolated","composeProject":"clawkit-autonomy-cli-test","service":"orders",
             "version":1,"stateless":true,"desiredState":"RUNNING","maintenanceUntil":null,"healthUri":"http://127.0.0.1:18180/health",
             "businessUri":"http://127.0.0.1:18180/orders","businessMarker":"accepted","checkInterval":"PT5S","evidenceTtl":"PT90S"},
             "policy":{"applicationId":"demo","applicationVersion":1,"version":1,"mode":"ASK","qualification":"DRAFT",
             "playbooks":["START_STOPPED_V1","RESTART_UNHEALTHY_V1"],"expiresAt":"%s","maxAttemptsPerIncident":1},
             "target":{"context":"default","daemonId":"test-daemon","endpoint":"unix:///var/run/docker.sock","composeFile":"compose.yaml",
             "composeHash":"%s","project":"clawkit-autonomy-cli-test","service":"orders","containerId":"%s","dependencies":{},"mounts":{}},"review":null}
            """.formatted(Instant.now().plusSeconds(3600),"a".repeat(64),"b".repeat(64)));
    }
    private record Result(int code,String output,String error) {}
}
