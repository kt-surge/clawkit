package com.clawkit.engine.impl;

import com.clawkit.engine.*;
import com.clawkit.provider.*;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.impl.WriteTool;
import com.clawkit.tools.schema.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class DeterministicFileVerificationTest {
    @TempDir Path workspace;

    @Test void ordinaryFileTaskFinishesWithinItsTwoMainModelCalls() throws Exception {
        var calls = new AtomicInteger();
        var gateway = new ProviderGateway() {
            @Override public ModelResponse generate(ModelRequest request, RunScope scope) {
                if (calls.incrementAndGet() == 1) {
                    return new ModelResponse(null, List.of(new ToolCall("write-result", "write",
                        new ObjectMapper().createObjectNode().put("path", "result.json")
                            .put("content", "{\"ready\":true}"))), FinishReason.TOOL_CALLS,
                        new TokenUsage(10, 2, 12), ProviderResponseMetadata.EMPTY);
                }
                return ModelResponse.text("completed", new TokenUsage(10, 2, 12));
            }
            @Override public ModelResponse generateStream(ModelRequest request, RunScope scope, StreamObserver observer) {
                return generate(request, scope);
            }
        };
        var registry = new ToolRegistry();
        registry.register(new WriteTool(workspace));
        var engine = new AgentEngine(new AgentRuntimeDependencies(gateway, null, registry, 128_000, null),
            workspace.toString(), ThinkingMode.OFF, "");
        engine.setPermissionMode(PermissionMode.AUTO);
        engine.setRunLimits(Duration.ofSeconds(10), 10_000L, 2L, 2L);
        assertThat(engine.run("Write result.json and finish.")).isEqualTo("completed");
        assertThat(Files.readString(workspace.resolve("result.json"))).isEqualTo("{\"ready\":true}");
        assertThat(calls).hasValue(2);
        try (var store = new com.clawkit.reliability.attempt.FileActionAttemptStore(
                workspace.resolve(".clawkit/reliability"))) {
            assertThat(store.byTarget(com.clawkit.tools.action.ActionTargets.fileTarget(workspace.resolve("result.json"))))
                .singleElement().satisfies(attempt -> assertThat(attempt.state())
                    .isEqualTo(com.clawkit.reliability.attempt.AttemptState.VERIFIED_SUCCESS));
        }
    }

    @Test void overwriteRefusalDoesNotLockAConfirmedCorrection() throws Exception {
        var calls = new AtomicInteger();
        var gateway = new ProviderGateway() {
            @Override public ModelResponse generate(ModelRequest request, RunScope scope) {
                int turn = calls.incrementAndGet();
                if (turn == 3) {
                    assertThat(request.messages().stream().map(m -> m.content() == null ? "" : m.content()))
                        .anyMatch(s -> s.contains("overwrite=true"))
                        .noneMatch(s -> s.contains("结果未知"));
                }
                if (turn <= 3) {
                    var args = new ObjectMapper().createObjectNode().put("path", "state.json")
                        .put("content", turn == 1 ? "revision-1" : "revision-2");
                    if (turn == 3) args.put("overwrite", true);
                    return new ModelResponse(null, List.of(new ToolCall("state-" + turn, "write", args)),
                        FinishReason.TOOL_CALLS, new TokenUsage(10, 2, 12), ProviderResponseMetadata.EMPTY);
                }
                return ModelResponse.text("completed", new TokenUsage(10, 2, 12));
            }
            @Override public ModelResponse generateStream(ModelRequest request, RunScope scope, StreamObserver observer) {
                return generate(request, scope);
            }
        };
        var registry = new ToolRegistry();
        registry.register(new WriteTool(workspace));
        var engine = new AgentEngine(new AgentRuntimeDependencies(gateway, null, registry, 128_000, null),
            workspace.toString(), ThinkingMode.OFF, "");
        engine.setPermissionMode(PermissionMode.AUTO);
        engine.setRunLimits(Duration.ofSeconds(10), 10_000L, 4L, 3L);
        assertThat(engine.run("Create state.json, then update it to revision-2.")).isEqualTo("completed");
        assertThat(Files.readString(workspace.resolve("state.json"))).isEqualTo("revision-2");
        assertThat(calls).hasValue(4);
        try (var store = new com.clawkit.reliability.attempt.FileActionAttemptStore(
                workspace.resolve(".clawkit/reliability"))) {
            assertThat(store.byTarget(com.clawkit.tools.action.ActionTargets.fileTarget(workspace.resolve("state.json"))))
                .extracting(a -> a.state()).containsExactlyInAnyOrder(
                    com.clawkit.reliability.attempt.AttemptState.VERIFIED_SUCCESS,
                    com.clawkit.reliability.attempt.AttemptState.FAILED_NO_EFFECT,
                    com.clawkit.reliability.attempt.AttemptState.VERIFIED_SUCCESS);
        }
    }
}
