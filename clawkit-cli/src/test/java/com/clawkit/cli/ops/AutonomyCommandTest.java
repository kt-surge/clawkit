package com.clawkit.cli.ops;

import com.clawkit.cli.ClawkitApp;
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
