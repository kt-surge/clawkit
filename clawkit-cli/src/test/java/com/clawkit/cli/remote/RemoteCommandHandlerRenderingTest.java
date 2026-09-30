package com.clawkit.cli.remote;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteCommandHandlerRenderingTest {

    @Test
    void shouldRenderToolNameFromRuntimeMountedName() {
        assertThat(RemoteCommandHandler.shortToolName(
            "mcp__remote_test_server__db_activity")).isEqualTo("db_activity");
    }

    @Test
    void shouldStripOnlyTheRuntimeRemotePrefixFromDescription() {
        assertThat(RemoteCommandHandler.stripRemoteDescriptionPrefix(
            "[MCP:remote:test-server] Read bounded PostgreSQL activity."))
            .isEqualTo("Read bounded PostgreSQL activity.");
    }

    @Test
    void shouldBuildInputSummaryFromMcpSchema() {
        String schema = """
            {"type":"object","properties":{"service":{"type":"string"},
            "limit":{"type":"integer"}},"required":["service"]}
            """;

        assertThat(RemoteCommandHandler.summarizeInputSchema(schema))
            .isEqualTo("service（必填）、limit（可选）");
    }

    @Test
    void shouldNotInventParametersWhenMcpSchemaHasNone() {
        assertThat(RemoteCommandHandler.summarizeInputSchema(
            "{\"type\":\"object\",\"properties\":{}}"))
            .isEqualTo("无");
    }
}
