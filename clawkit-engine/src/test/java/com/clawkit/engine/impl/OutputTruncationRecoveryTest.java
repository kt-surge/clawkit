package com.clawkit.engine.impl;

import com.clawkit.engine.*;
import com.clawkit.observability.*;
import com.clawkit.provider.*;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.impl.WriteTool;
import com.clawkit.tools.schema.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Paid rejected responses never become executable tool calls; recovery shares ordinary run limits. */
class OutputTruncationRecoveryTest {
    @TempDir Path workspace;
    private static final TokenUsage PAID = new TokenUsage(100, 2048, 2148);
    private static final TokenUsage SUCCESS = new TokenUsage(10, 2, 12);
    private static final String RAW = "UNTRUSTED_SECRET {\"tool_calls\":[{\"name\":\"write\",\"arguments\":{\"path\":\"must-not-execute.txt\",\"content\":\"partial\"}}]}";
    private static LLMException rejected(String phase, TokenUsage usage) {
        return new LLMException("Model response did not complete: " + phase, null,
            new ProviderError.Protocol("incomplete response"), 0,
            RejectedModelResponse.bounded(phase, RAW, usage));
    }
    private static ModelResponse writeResult() {
        return new ModelResponse(null, List.of(new ToolCall("valid-write", "write",
            new ObjectMapper().createObjectNode().put("path", "result.json").put("content", "{\"ready\":true}"))),
            FinishReason.TOOL_CALLS, SUCCESS, ProviderResponseMetadata.EMPTY);
    }
    private class Fixture {
        final List<ModelRequest> requests = new ArrayList<>();
        final List<RunEventPayload> events = new ArrayList<>();
        final AgentEngine engine;
        Fixture(BiFunction<ModelRequest, Integer, ModelResponse> script) {
            LLMProvider provider = new LLMProvider() {
                @Override public Message generate(List<Message> messages, List<ToolDefinition> tools) { throw new AssertionError("V2 required"); }
                @Override public ModelResponse generate(ModelRequest request) {
                    requests.add(request); return script.apply(request, requests.size());
                }
            };
            RunRecorder recorder = (payload, runId, parentRunId, turn, time) -> events.add(payload);
            var registry = new ToolRegistry(); registry.register(new WriteTool(workspace));
            engine = new AgentEngine(new AgentRuntimeDependencies(new ObservingProviderGateway(provider, recorder), null,
                registry, 128_000, "cl100k_base", recorder, AgentRuntimeDependencies.noopMemoryHooks(),
                AgentRuntimeDependencies.emptySkillRuntime()), workspace.toString(), ThinkingMode.OFF, "");
            engine.setPermissionMode(PermissionMode.AUTO);
            engine.setRunLimits(Duration.ofSeconds(10), 30_000L, 10L, 4L);
        }
        List<ProviderCallCompletedPayload> providerEvents() {
            return events.stream().filter(ProviderCallCompletedPayload.class::isInstance)
                .map(ProviderCallCompletedPayload.class::cast).toList();
        }
        RunStatus status() {
            return events.stream().filter(RunCompletedPayload.class::isInstance)
                .map(RunCompletedPayload.class::cast).reduce((first, last) -> last).orElseThrow().status();
        }
    }
    @Test void recoversWithoutExecutingPartialToolsAndKeepsPaidFailureUsage() throws Exception {
        var fixture = new Fixture((request, call) -> {
            if (call == 1) throw rejected("OUTPUT_TRUNCATED", PAID);
            if (call == 2) return writeResult();
            return ModelResponse.text("completed", SUCCESS);
        });
        assertThat(fixture.engine.run("Write result.json and finish.")).isEqualTo("completed");
        assertThat(Files.readString(workspace.resolve("result.json"))).isEqualTo("{\"ready\":true}");
        assertThat(workspace.resolve("must-not-execute.txt")).doesNotExist();
        assertThat(fixture.requests).hasSize(3);
        assertThat(fixture.requests.get(1).messages()).anyMatch(message -> message.role() == Role.SYSTEM
            && message.content() != null && message.content().contains("[Runtime][Output Recovery]")
            && message.content().contains("at most two small tool calls"));
        assertThat(fixture.requests.stream().flatMap(r -> r.messages().stream()))
            .noneMatch(message -> message.content() != null && message.content().contains("UNTRUSTED_SECRET"));
        assertThat(fixture.requests).allSatisfy(request ->
            assertThat(request.parameters()).isEqualTo(fixture.requests.getFirst().parameters()));
        assertThat(fixture.providerEvents()).hasSize(3).allSatisfy(event -> {
            assertThat(event.retryCount()).isZero(); assertThat(event.usageSource()).isEqualTo("ACTUAL");
        });
        assertThat(fixture.providerEvents().getFirst().failed()).isTrue();
        assertThat(fixture.providerEvents().stream().mapToInt(e -> e.inputTokens() + e.outputTokens()).sum()).isEqualTo(2172);
        assertThat(fixture.status()).isEqualTo(RunStatus.COMPLETED);
        try (var store = new com.clawkit.reliability.attempt.FileActionAttemptStore(workspace.resolve(".clawkit/reliability"))) {
            assertThat(store.byTarget(com.clawkit.tools.action.ActionTargets.fileTarget(workspace.resolve("result.json"))))
                .singleElement().satisfies(attempt -> assertThat(attempt.state())
                    .isEqualTo(com.clawkit.reliability.attempt.AttemptState.VERIFIED_SUCCESS));
        }
        assertThat(fixture.engine.run("A separate next task.")).isEqualTo("completed");
        assertThat(fixture.requests.getLast().messages()).noneMatch(message -> message.content() != null
            && message.content().contains("[Runtime][Output Recovery]"));
    }
    @Test void repeatedTruncationStopsAfterTwoRecoveryRequests() {
        var fixture = new Fixture((request, call) -> { throw rejected("OUTPUT_TRUNCATED", PAID); });
        assertThat(fixture.engine.run("Write result.json.")).startsWith("[A-002]");
        assertThat(fixture.requests).hasSize(3);
        assertThat(fixture.providerEvents()).hasSize(3).allSatisfy(event -> assertThat(event.failed()).isTrue());
        assertThat(fixture.providerEvents().stream().mapToInt(e -> e.inputTokens() + e.outputTokens()).sum()).isEqualTo(6444);
        assertThat(fixture.status()).isEqualTo(RunStatus.LLM_ERROR);
        assertThat(workspace.resolve("must-not-execute.txt")).doesNotExist();
    }
    @Test void otherCompletionFailuresAreNotRecovered() {
        for (String phase : List.of("CONTENT_FILTERED", "INCOMPLETE_COMPLETION")) {
            var fixture = new Fixture((request, call) -> { throw rejected(phase, PAID); });
            assertThat(fixture.engine.run("Write result.json.")).startsWith("[A-002]");
            assertThat(fixture.requests).hasSize(1); assertThat(fixture.status()).isEqualTo(RunStatus.LLM_ERROR);
        }
    }
    @Test void unknownOrInconsistentUsageIsNotRecovered() {
        for (TokenUsage usage : List.of(TokenUsage.EMPTY, new TokenUsage(100, 2048, 1))) {
            var fixture = new Fixture((request, call) -> { throw rejected("OUTPUT_TRUNCATED", usage); });
            assertThat(fixture.engine.run("Write result.json.")).startsWith("[A-002]");
            assertThat(fixture.requests).hasSize(1); assertThat(fixture.status()).isEqualTo(RunStatus.LLM_ERROR);
        }
    }
    @Test void transportErrorsDoNotTriggerSemanticRecovery() {
        var fixture = new Fixture((request, call) -> { throw new LLMException("network timeout"); });
        assertThat(fixture.engine.run("Write result.json.")).startsWith("[A-002]");
        assertThat(fixture.requests).hasSize(1); assertThat(fixture.status()).isEqualTo(RunStatus.LLM_ERROR);
    }
    @Test void recoveryCannotBypassCallBudget() {
        var fixture = new Fixture((request, call) -> { throw rejected("OUTPUT_TRUNCATED", PAID); });
        fixture.engine.setRunLimits(Duration.ofSeconds(10), 30_000L, 1L, 4L);
        assertThat(fixture.engine.run("Write result.json.")).startsWith("[A-007]");
        assertThat(fixture.requests).hasSize(1); assertThat(fixture.status()).isEqualTo(RunStatus.BUDGET_EXHAUSTED);
    }
    @Test void cancellationStopsRecoveryBeforeAnotherProviderCall() {
        var engine = new java.util.concurrent.atomic.AtomicReference<AgentEngine>();
        var fixture = new Fixture((request, call) -> { engine.get().interrupt(); throw rejected("OUTPUT_TRUNCATED", PAID); });
        engine.set(fixture.engine);
        assertThat(fixture.engine.run("Write result.json.")).startsWith("[A-001]");
        assertThat(fixture.requests).hasSize(1); assertThat(fixture.status()).isEqualTo(RunStatus.INTERRUPTED);
    }
}
