package com.clawkit.engine.impl;

import com.clawkit.engine.*;
import com.clawkit.observability.*;
import com.clawkit.provider.*;
import com.clawkit.tools.*;
import com.clawkit.tools.impl.*;
import com.clawkit.tools.schema.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class TaskCompletionCheckTest {
    @TempDir Path workspace;
    static final ObjectMapper JSON = new ObjectMapper();
    static final TokenUsage PAID = new TokenUsage(10, 2, 12);
    static ModelResponse tools(ToolCall call) {
        return new ModelResponse(null, List.of(call), FinishReason.TOOL_CALLS, PAID, ProviderResponseMetadata.EMPTY);
    }
    static ToolCall read(String id) {
        return new ToolCall(id, "read", JSON.createObjectNode().put("path", "profile.json"));
    }
    static ToolCall write(String id, String body, boolean overwrite) {
        return new ToolCall(id, "write", JSON.createObjectNode().put("path", "result.json")
            .put("content", body).put("overwrite", overwrite));
    }
    class Fixture {
        final List<ModelRequest> requests = new ArrayList<>();
        final List<RunEventPayload> events = new ArrayList<>();
        final AgentEngine engine;
        Fixture(BiFunction<ModelRequest, Integer, ModelResponse> script) {
            LLMProvider provider = new LLMProvider() {
                @Override public Message generate(List<Message> m, List<ToolDefinition> t) { throw new AssertionError("typed gateway required"); }
                @Override public ModelResponse generate(ModelRequest r) { requests.add(r); return script.apply(r, requests.size()); }
            };
            RunRecorder recorder = (p,rid,pid,turn,time) -> events.add(p);
            var registry = new ToolRegistry(); registry.register(new ReadTool(workspace)); registry.register(new WriteTool(workspace));
            engine = new AgentEngine(new AgentRuntimeDependencies(new ObservingProviderGateway(provider, recorder), null,
                registry, 128000, "cl100k_base", recorder, AgentRuntimeDependencies.noopMemoryHooks(),
                AgentRuntimeDependencies.emptySkillRuntime()), workspace.toString(), ThinkingMode.OFF, "");
            engine.setRunLimits(Duration.ofSeconds(10), 30000L, 20L, 10L);
        }
        RunCompletedPayload completion() {
            return events.stream().filter(RunCompletedPayload.class::isInstance).map(RunCompletedPayload.class::cast)
                .reduce((a,b)->b).orElseThrow();
        }
        String run(TaskCompletionCheck check) { return engine.run("Use profile.json to create result.json.", RunToolScope.ALL, check); }
    }

    @Test void rejectedBusinessResultIsCorrectedThroughNativeReadAndWriteBeforeCompletion() throws Exception {
        Files.writeString(workspace.resolve("profile.json"), "{\"domain\":\"service.example\"}");
        var fixture = new Fixture((request, step) -> {
            if (step == 1) return tools(read("first-read"));
            if (step == 2) return tools(write("bad-write", "{\"domain\":\"service.prod.example\"}", false));
            if (step == 4) {
                assertThat(request.messages()).anyMatch(m -> m.content()!=null && m.content().contains("[Runtime][Task Acceptance]"));
                return tools(read("fresh-read"));
            }
            if (step == 5) {
                String source = request.messages().stream().filter(m -> "fresh-read".equals(m.toolCallId()))
                    .map(Message::content).findFirst().orElseThrow();
                return tools(write("correct-write", source, true));
            }
            return ModelResponse.text("done", PAID);
        });
        var checks = new AtomicInteger();
        TaskCompletionCheck check = request -> {
            checks.incrementAndGet(); request.control().checkpoint();
            try {
                var source = JSON.readTree(Files.readString(workspace.resolve("profile.json")));
                var output = JSON.readTree(Files.readString(workspace.resolve("result.json")));
                return source.get("domain").equals(output.get("domain")) ? TaskCompletionCheck.Result.accept()
                    : TaskCompletionCheck.Result.retry("DOMAIN_MISMATCH", "result.json domain must match the latest profile.json domain; re-read it.");
            } catch (java.io.IOException e) { return TaskCompletionCheck.Result.reject("READ_FAILED", "Cannot inspect declared files."); }
        };
        assertThat(fixture.run(check)).isEqualTo("done");
        assertThat(fixture.requests).hasSize(6);
        assertThat(checks).hasValue(2);
        assertThat(Files.readString(workspace.resolve("result.json"))).isEqualTo(Files.readString(workspace.resolve("profile.json")));
        assertThat(fixture.completion().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fixture.events.stream().filter(RunCompletedPayload.class::isInstance)).hasSize(1);
        assertThat(fixture.events.stream().filter(ProviderCallCompletedPayload.class::isInstance)
            .map(ProviderCallCompletedPayload.class::cast).mapToInt(e -> e.inputTokens()+e.outputTokens()).sum()).isEqualTo(72);
        fixture.engine.run("A separate run with no check.");
        assertThat(checks).hasValue(2);
        assertThat(fixture.requests.getLast().messages()).noneMatch(m -> m.content()!=null && m.content().contains("[Runtime][Task Acceptance]"));
    }


    @Test void callerChecksLatestEvidenceAndDeclaredActionRuleBeforeAccepting() throws Exception {
        Files.writeString(workspace.resolve("profile.json"), "{\"revision\":1,\"authorized\":true,\"epoch\":10,\"cooldownUntil\":20}");
        var fixture = new Fixture((request, step) -> {
            if (step == 1) return tools(read("initial-evidence"));
            if (step == 2) return tools(write("stale-decision", "{\"revision\":1,\"action\":\"WAIT\"}", false));
            if (step == 3) {
                try { Files.writeString(workspace.resolve("profile.json"), "{\"revision\":2,\"authorized\":true,\"epoch\":30,\"cooldownUntil\":20}"); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            }
            if (step == 4) return tools(read("latest-evidence"));
            if (step == 5) {
                try {
                    var source = JSON.readTree(request.messages().stream().filter(m -> "latest-evidence".equals(m.toolCallId()))
                        .map(Message::content).findFirst().orElseThrow());
                    String action = source.get("epoch").asInt() < source.get("cooldownUntil").asInt() ? "WAIT"
                        : source.get("authorized").asBoolean() ? "PROPOSE" : "REVIEW";
                    return tools(write("fresh-decision", JSON.createObjectNode().put("revision",source.get("revision").asInt())
                        .put("action",action).toString(), true));
                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            }
            return ModelResponse.text("done", PAID);
        });
        var checks = new AtomicInteger();
        TaskCompletionCheck check = request -> {
            checks.incrementAndGet();
            try {
                var source = JSON.readTree(Files.readString(workspace.resolve("profile.json")));
                var output = JSON.readTree(Files.readString(workspace.resolve("result.json")));
                if (!source.get("revision").equals(output.get("revision")))
                    return TaskCompletionCheck.Result.retry("STALE_EVIDENCE", "Read current profile.json and update result.json.");
                String expected = source.get("epoch").asInt() < source.get("cooldownUntil").asInt() ? "WAIT"
                    : source.get("authorized").asBoolean() ? "PROPOSE" : "REVIEW";
                return expected.equals(output.get("action").asText()) ? TaskCompletionCheck.Result.accept()
                    : TaskCompletionCheck.Result.retry("ACTION_MISMATCH", "Apply declared cooldown and authorization order.");
            } catch (java.io.IOException e) { return TaskCompletionCheck.Result.reject("READ_FAILED", "Declared evidence unavailable."); }
        };
        assertThat(fixture.run(check)).isEqualTo("done");
        assertThat(checks).hasValue(2); assertThat(fixture.requests).hasSize(6);
        var result = JSON.readTree(Files.readString(workspace.resolve("result.json")));
        assertThat(result.get("revision").asInt()).isEqualTo(2);
        assertThat(result.get("action").asText()).isEqualTo("PROPOSE");
        assertThat(fixture.completion().status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test void repeatedRejectionStopsAfterTwoCorrectionsWithoutFalseSuccess() {
        var fixture = new Fixture((r,n) -> ModelResponse.text("done", PAID));
        var checks = new AtomicInteger();
        assertThat(fixture.run(r -> {checks.incrementAndGet();return TaskCompletionCheck.Result.retry("WRONG_ACTION", "Check current eligibility.");}))
            .startsWith("[A-011]");
        assertThat(checks).hasValue(3); assertThat(fixture.requests).hasSize(3);
        assertThat(fixture.completion().status()).isEqualTo(RunStatus.UNKNOWN_ERROR);
        assertThat(fixture.completion().errorCode()).isEqualTo("CHECK_RETRY_LIMIT");
    }

    @Test void applicationRejectAndUnknownOrInvalidResultsFailClosedWithoutRawExceptionLeak() {
        for (TaskCompletionCheck check : List.<TaskCompletionCheck>of(
            r -> TaskCompletionCheck.Result.reject("RULE_REJECTED", "needs human review"),
            r -> null,
            r -> {throw new IllegalStateException("PRIVATE_VALIDATOR_EXCEPTION");},
            r -> TaskCompletionCheck.Result.retry("INVALID", "x".repeat(2049)))) {
            var fixture = new Fixture((r,n) -> ModelResponse.text("done", PAID));
            assertThat(fixture.run(check)).startsWith("[A-011]").doesNotContain("PRIVATE_VALIDATOR_EXCEPTION");
            assertThat(fixture.requests).hasSize(1);
            assertThat(fixture.completion().status()).isEqualTo(RunStatus.UNKNOWN_ERROR);
        }
    }

    @Test void correctionSharesExistingProviderBudget() {
        var fixture = new Fixture((r,n) -> ModelResponse.text("done", PAID));
        fixture.engine.setRunLimits(Duration.ofSeconds(10), 30000L, 1L, 10L);
        assertThat(fixture.run(r -> TaskCompletionCheck.Result.retry("WRONG_VALUE", "re-read source"))).startsWith("[A-007]");
        assertThat(fixture.requests).hasSize(1); assertThat(fixture.completion().status()).isEqualTo(RunStatus.BUDGET_EXHAUSTED);
    }

    @Test void cancellationInsideCheckDoesNotAcceptOrLaunchAnotherModelCall() {
        var fixture = new Fixture((r,n) -> ModelResponse.text("done", PAID));
        assertThat(fixture.run(r -> {fixture.engine.interrupt();return TaskCompletionCheck.Result.accept();})).startsWith("[A-001]");
        assertThat(fixture.requests).hasSize(1); assertThat(fixture.completion().status()).isEqualTo(RunStatus.INTERRUPTED);
    }


    @Test void acceptanceFeedbackCannotExpandRestrictedToolScope() {
        var fixture = new Fixture((r,n) -> n==2 ? tools(write("not-allowed", "must not be written", false))
            : ModelResponse.text("done", PAID));
        var checks = new AtomicInteger();
        String result = fixture.engine.run("Inspect remote status only.", RunToolScope.REMOTE_READ_ONLY,
            r -> checks.incrementAndGet()==1 ? TaskCompletionCheck.Result.retry("RETRY", "Write result.json")
                : TaskCompletionCheck.Result.reject("FAILED", "Result remains unaccepted"));
        assertThat(result).startsWith("[A-011]");
        assertThat(workspace.resolve("result.json")).doesNotExist();
        assertThat(fixture.requests).hasSize(3);
        assertThat(fixture.completion().status()).isEqualTo(RunStatus.UNKNOWN_ERROR);
    }

    @Test void checkCannotBeSilentlyIgnoredByPlanExecute() {
        var fixture = new Fixture((r,n) -> ModelResponse.text("done", PAID));
        fixture.engine.setExecutionMode(ExecutionMode.PLAN_EXECUTE);
        var checks = new AtomicInteger();
        assertThat(fixture.run(r -> {checks.incrementAndGet();return TaskCompletionCheck.Result.accept();})).startsWith("[A-011]");
        assertThat(fixture.requests).isEmpty(); assertThat(checks).hasValue(0);
    }
}
