package com.clawkit.engine.impl;

import com.clawkit.tools.DefaultApprovalGrantCache;
import com.clawkit.tools.PermissionMode;
import com.clawkit.tools.Result;
import com.clawkit.tools.RunToolScope;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolExecutionStatus;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.schema.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolScopeExecutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void remoteReadOnlyScopeBlocksRemoteWriteBeforeToolStarts() {
        var restart = new CountingTool("mcp__remote_test_server__restart_service", false);
        var result = execute(restart, RunToolScope.REMOTE_READ_ONLY);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.BLOCKED);
        assertThat(restart.calls.get()).isZero();
    }

    @Test
    void remoteReadOnlyScopeAllowsRemoteReadTool() {
        var status = new CountingTool("mcp__remote_test_server__service_status", true);
        var result = execute(status, RunToolScope.REMOTE_READ_ONLY);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCESS);
        assertThat(status.calls.get()).isEqualTo(1);
    }

    @Test
    void remoteReadOnlyScopeBlocksHallucinatedLocalToolBeforeToolStarts() {
        var bash = new CountingTool("bash", true);
        var result = execute(bash, RunToolScope.REMOTE_READ_ONLY);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.BLOCKED);
        assertThat(bash.calls.get()).isZero();
    }

    private static com.clawkit.tools.ToolExecutionResult execute(Tool tool, RunToolScope scope) {
        var registry = new ToolRegistry();
        registry.register(tool);
        var context = new ToolExecutionContext(
            "scope-test", 1, PermissionMode.AUTO, new DefaultPermissionPolicy(),
            null, null, new InternalToolRouter(), new DefaultApprovalGrantCache(),
            com.clawkit.tools.control.ExecutionControl.none(), scope);
        return new ToolCallExecutor(registry).executeBatch(List.of(
            new ToolCall("call-1", tool.name(), MAPPER.createObjectNode())), context)
            .results().getFirst();
    }

    private static final class CountingTool implements Tool {
        private final String name;
        private final boolean readOnly;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingTool(String name, boolean readOnly) {
            this.name = name;
            this.readOnly = readOnly;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "test tool"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public boolean isReadOnly() { return readOnly; }

        @Override
        public Result<String> execute(String arguments) {
            calls.incrementAndGet();
            return new Result.Ok<>("ok");
        }
    }
}
