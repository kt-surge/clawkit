package com.clawkit.evaluation.context;

import com.clawkit.context.*;
import com.clawkit.context.impl.*;
import com.clawkit.engine.*;
import com.clawkit.provider.*;
import com.clawkit.tools.schema.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class FixedToolSurfaceTest {
    @Test void internalMemoryAndDelegationToolsAreNeitherOfferedNorExecutable() {
        var recorded = new AtomicReference<ModelRequest>();
        ProviderGateway delegate = gateway(request -> {
            recorded.set(request);
            return new ModelResponse(null, List.of(new ToolCall("delegate", "task", EvaluationArtifacts.JSON.createObjectNode())),
                FinishReason.TOOL_CALLS, new TokenUsage(20, 10, 30), ProviderResponseMetadata.EMPTY);
        });
        var request = ModelRequest.of(List.of(Message.user("synthetic")), List.of(def("read"), def("task"), def("memory_save")));
        assertThatThrownBy(() -> new FixedToolEvaluationGateway(delegate).generate(request, scope())).isInstanceOfSatisfying(LLMException.class,
            failure -> assertThat(failure.rejectedResponse().usage().totalTokens()).isEqualTo(30));
        assertThat(recorded.get().tools()).extracting(ToolDefinition::name).containsExactly("read");
    }
    @Test void independentReadOnlyVerifierCannotGainWriteFromEvaluationAllowlist() {
        var gateway = new FixedToolEvaluationGateway(gateway(request -> new ModelResponse(null,
            List.of(new ToolCall("illegal-write", "write", EvaluationArtifacts.JSON.createObjectNode().put("path", "out.json"))),
            FinishReason.TOOL_CALLS, new TokenUsage(20, 10, 30), ProviderResponseMetadata.EMPTY)));
        assertThatThrownBy(() -> gateway.generate(ModelRequest.of(List.of(Message.user("verify")), List.of(def("read"))), scope()))
            .isInstanceOf(LLMException.class).hasMessage("EVALUATION_UNOFFERED_TOOL");
    }
    @Test void contextReportCountsExactlyTheOfferedToolsAndDeclaredOutputReserve() {
        var captured = new AtomicReference<CompactionRequest>();
        var tokenizer = new CharFallbackTokenizer();
        var budget = ContextBudgetPolicy.of(4096);
        var production = new DefaultContextPipeline(new LadderedCompactor(null, tokenizer), new ContextBudgetAnalyzer(tokenizer, budget), tokenizer, budget);
        ContextPipeline delegate = new ContextPipeline() {
            @Override public ModelContext build(ContextRequest r) { return production.build(r); }
            @Override public CompactionResult compact(CompactionRequest r) { captured.set(r); return production.compact(r); }
        };
        var pipeline = new FixedToolContextPipeline(delegate, tokenizer, 1024);
        var context = pipeline.build(new ContextRequest("system", List.of(Message.user("task")), List.of(), List.of(), List.of(), List.of(),
            List.of(def("read"), new ToolDefinition("task", "long internal description".repeat(200), "{}")), budget));
        pipeline.compact(new CompactionRequest(context.messages(), 9999, 1, CompactionHint.GENERAL, 64, 32, 30000));
        assertThat(captured.get().toolDefTokens()).isLessThan(100);
        assertThat(captured.get().reservedOutputTokens()).isEqualTo(1024);
    }
    @Test void newVerificationRootCannotResetTheSharedToolProposalLimit() {
        var count = com.clawkit.reliability.WorkBudgetLedger.of(8, 1);
        var bounded = new FixedToolEvaluationGateway(gateway(request -> new ModelResponse(null,
            List.of(new ToolCall("read-one", "read", EvaluationArtifacts.JSON.createObjectNode().put("path", "out.json"))),
            FinishReason.TOOL_CALLS, new TokenUsage(20, 10, 30), ProviderResponseMetadata.EMPTY)), count);
        bounded.generate(ModelRequest.of(List.of(Message.user("task")), List.of(def("read"))), scope());
        assertThatThrownBy(() -> bounded.generate(ModelRequest.of(List.of(Message.user("fresh verifier")), List.of(def("read"))),
            new RunScope("fresh-root", null, 1, RunPhase.REACT, ExecutionMode.REACT))).isInstanceOf(LLMException.class)
            .hasMessage("EVALUATION_INSTANCE_TOOL_CALL_LIMIT");
        assertThat(count.remainingToolCalls()).isZero();
    }
    private static ToolDefinition def(String name) { return new ToolDefinition(name, "description", "{}"); }
    private static RunScope scope() { return new RunScope("surface", null, 1, RunPhase.REACT, ExecutionMode.REACT); }
    private static ProviderGateway gateway(java.util.function.Function<ModelRequest, ModelResponse> behavior) {
        return new ProviderGateway() {
            @Override public ModelResponse generate(ModelRequest r, RunScope s) { return behavior.apply(r); }
            @Override public ModelResponse generateStream(ModelRequest r, RunScope s, StreamObserver o) { return generate(r, s); }
        };
    }
}
