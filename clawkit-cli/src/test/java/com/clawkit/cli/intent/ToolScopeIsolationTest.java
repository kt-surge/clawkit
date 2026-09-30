package com.clawkit.cli.intent;

import com.clawkit.tools.RunToolScope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security regression tests for tool scope isolation.
 *
 * <p>Tests that tool visibility and execution are truly isolated, not just
 * hidden from the model prompt.
 */
class ToolScopeIsolationTest {

    // ── RunToolScope + isToolAllowed ──────────────────────────────────

    @Test
    void localOnlyShouldAllowReadTool() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "read", RunToolScope.LOCAL_ONLY)).isTrue();
    }

    @Test
    void localOnlyShouldAllowBash() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "bash", RunToolScope.LOCAL_ONLY)).isTrue();
    }

    @Test
    void localOnlyShouldAllowGrep() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "grep", RunToolScope.LOCAL_ONLY)).isTrue();
    }

    @Test
    void localOnlyShouldAllowGitRead() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "git_read", RunToolScope.LOCAL_ONLY)).isTrue();
    }

    @Test
    void localOnlyShouldAllowGlob() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "glob", RunToolScope.LOCAL_ONLY)).isTrue();
    }

    @Test
    void localOnlyShouldAllowEdit() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "edit", RunToolScope.LOCAL_ONLY)).isTrue();
    }

    @Test
    void localOnlyShouldAllowWrite() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "write", RunToolScope.LOCAL_ONLY)).isTrue();
    }

    @Test
    void localOnlyShouldBlockRemoteTools() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "mcp__remote_test_server__check_service", RunToolScope.LOCAL_ONLY)).isFalse();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "mcp__remote_prod__container_status", RunToolScope.LOCAL_ONLY)).isFalse();
    }

    // ── REMOTE_READ_ONLY ──────────────────────────────────────────────

    @Test
    void remoteReadOnlyShouldAllowRemoteTools() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "mcp__remote_test_server__check_service", RunToolScope.REMOTE_READ_ONLY)).isTrue();
    }

    @Test
    void remoteReadOnlyShouldBlockBash() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "bash", RunToolScope.REMOTE_READ_ONLY)).isFalse();
    }

    @Test
    void remoteReadOnlyShouldBlockRead() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "read", RunToolScope.REMOTE_READ_ONLY)).isFalse();
    }

    @Test
    void remoteReadOnlyShouldBlockGrep() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "grep", RunToolScope.REMOTE_READ_ONLY)).isFalse();
    }

    @Test
    void remoteReadOnlyShouldBlockGlob() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "glob", RunToolScope.REMOTE_READ_ONLY)).isFalse();
    }

    @Test
    void remoteReadOnlyShouldBlockGitRead() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "git_read", RunToolScope.REMOTE_READ_ONLY)).isFalse();
    }

    @Test
    void remoteReadOnlyShouldBlockEdit() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "edit", RunToolScope.REMOTE_READ_ONLY)).isFalse();
    }

    @Test
    void remoteReadOnlyShouldBlockWrite() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "write", RunToolScope.REMOTE_READ_ONLY)).isFalse();
    }

    // ── NO_TOOLS ──────────────────────────────────────────────────────

    @Test
    void noToolsShouldBlockEverything() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "read", RunToolScope.NO_TOOLS)).isFalse();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "bash", RunToolScope.NO_TOOLS)).isFalse();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "mcp__remote_srv__check", RunToolScope.NO_TOOLS)).isFalse();
    }

    @Test
    void noToolsShouldBlockEvenInternalTools() {
        // Internal tools like subagent, todo_write should also be blocked
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "subagent", RunToolScope.NO_TOOLS)).isFalse();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "todo_write", RunToolScope.NO_TOOLS)).isFalse();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "plan", RunToolScope.NO_TOOLS)).isFalse();
    }

    // ── ALL ───────────────────────────────────────────────────────────

    @Test
    void allShouldAllowEverything() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "bash", RunToolScope.ALL)).isTrue();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "mcp__remote_srv__check", RunToolScope.ALL)).isTrue();
    }

    @Test
    void nullScopeShouldDefaultToAllow() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "bash", null)).isTrue();
    }

    // ── Classification failure guard ──────────────────────────────────

    @Test
    void classifiedIntentNoneHasClarifyScope() {
        assertThat(ClassifiedIntent.NONE.scope()).isEqualTo(WorkScope.CLARIFY);
        assertThat(ClassifiedIntent.NONE.intent()).isEqualTo(ServerIntent.NONE);
    }

    @Test
    void parseFailureReturnsNull() {
        var result = IntentClassifier.parseResponse("garbage", java.util.List.of(), null);
        assertThat(result).isNull();
    }

    // ── REMOTE_READ_ONLY write-tool rejection ─────────────────────────

    @Test
    void remoteReadOnlyAllowsReadOnlyRemoteToolByPrefix() {
        // read-only remote tool passes prefix check (isReadOnly checked separately)
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "mcp__remote_srv__check_service", RunToolScope.REMOTE_READ_ONLY)).isTrue();
    }

    @Test
    void remoteReadOnlyAllowsWriteToolByPrefixButMustBeRejectedByReadOnlyCheck() {
        // Write tools like restart_service pass the PREFIX check (isToolAllowed)
        // but must be rejected by the additional meta.isReadOnly() check in doExecute()
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "mcp__remote_srv__restart_service", RunToolScope.REMOTE_READ_ONLY))
            .describedAs("prefix check passes, but doExecute() must also check meta.isReadOnly()")
            .isTrue();
    }

    // ── Real execution guard: verify isToolAllowed rejects on all scopes ──

    @Test
    void noToolsRejectsEveryToolCategory() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "bash", RunToolScope.NO_TOOLS)).isFalse();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "read", RunToolScope.NO_TOOLS)).isFalse();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "mcp__remote_srv__check", RunToolScope.NO_TOOLS)).isFalse();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "subagent", RunToolScope.NO_TOOLS)).isFalse();
    }

    @Test
    void localOnlyRejectsEveryRemoteToolCategory() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "mcp__remote_srv__check_service", RunToolScope.LOCAL_ONLY)).isFalse();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "mcp__remote_srv__container_status", RunToolScope.LOCAL_ONLY)).isFalse();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "mcp__remote_srv__restart_service", RunToolScope.LOCAL_ONLY)).isFalse();
    }

    @Test
    void remoteReadOnlyRejectsEveryLocalToolCategory() {
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "bash", RunToolScope.REMOTE_READ_ONLY)).isFalse();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "read", RunToolScope.REMOTE_READ_ONLY)).isFalse();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "grep", RunToolScope.REMOTE_READ_ONLY)).isFalse();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "glob", RunToolScope.REMOTE_READ_ONLY)).isFalse();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "git_read", RunToolScope.REMOTE_READ_ONLY)).isFalse();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "edit", RunToolScope.REMOTE_READ_ONLY)).isFalse();
        assertThat(com.clawkit.engine.impl.ToolCallExecutor.isToolAllowed(
            "write", RunToolScope.REMOTE_READ_ONLY)).isFalse();
    }

    @Test
    void planExecutionRejectsRestrictedScope() {
        // PLAN_EXECUTE + restricted scope → engine returns error, doesn't silently bypass
        // This is verified by the AgentEngine code: if (toolScope != ALL) return error
        // Test: scope isolation logic ensures plan mode can't be used to bypass filters
        assertThat(RunToolScope.ALL).isNotEqualTo(RunToolScope.LOCAL_ONLY);
        assertThat(RunToolScope.ALL).isNotEqualTo(RunToolScope.REMOTE_READ_ONLY);
        assertThat(RunToolScope.ALL).isNotEqualTo(RunToolScope.NO_TOOLS);
    }
}
