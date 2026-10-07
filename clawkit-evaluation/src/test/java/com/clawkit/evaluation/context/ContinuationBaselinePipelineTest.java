package com.clawkit.evaluation.context;

import com.clawkit.context.*;
import com.clawkit.context.impl.*;
import com.clawkit.engine.*;
import com.clawkit.provider.*;
import com.clawkit.tools.schema.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ContinuationBaselinePipelineTest {
    @Test void truncatesWholeTurnsWithoutLeavingOrphanToolResults() {
        var pipeline = pipeline(ContinuationBaselinePipeline.Mode.RECENT_WHOLE_TURNS, null);
        var result = pipeline.compact(new CompactionRequest(history(6), 0, 6));
        assertThat(result.audit().failed()).isFalse();
        assertThat(result.audit().evictedGroups()).isEqualTo(4);
        assertThat(result.messages()).filteredOn(m -> m.role() == Role.USER).hasSize(2);
        var calls = result.messages().stream().filter(m -> m.toolCalls() != null).flatMap(m -> m.toolCalls().stream()).map(ToolCall::id).toList();
        assertThat(result.messages().stream().filter(m -> m.role() == Role.TOOL).map(Message::toolCallId)).containsExactlyElementsOf(calls);
        assertThat(result.messages()).contains(Message.system("stable kernel"));
    }

    @Test void summaryUsesEvictedHistoryAndPreviousSummaryWithoutGrantingItSystemAuthority() {
        var inputs = new ArrayList<ModelRequest>();
        var pipeline = pipeline(ContinuationBaselinePipeline.Mode.ROLLING_SUMMARY, gateway(inputs, "bounded previous decisions"));
        var first = pipeline.compact(new CompactionRequest(history(6), 0, 6));
        assertThat(first.audit().failed()).isFalse();
        assertThat(inputs).hasSize(1);
        assertThat(inputs.getFirst().messages().getLast().content()).contains("question 1", "call-1");
        var next = new ArrayList<>(first.messages());
        next.addAll(history(4).subList(1, history(4).size()));
        pipeline.compact(new CompactionRequest(next, 0, 10));
        assertThat(inputs).hasSize(2);
        assertThat(inputs.getLast().messages().getLast().content()).contains("bounded previous decisions");
        assertThat(inputs.getLast().messages().getFirst().content()).doesNotContain("bounded previous decisions");
    }

    @Test void rejectsInvalidSummaryAndOversizedRecentTurnRatherThanReportingSuccess() {
        var invalid = pipeline(ContinuationBaselinePipeline.Mode.ROLLING_SUMMARY, gateway(new ArrayList<>(), ""));
        assertThat(invalid.compact(new CompactionRequest(history(6), 0, 6)).audit().failureCode()).isEqualTo("BASELINE_SUMMARY_INVALID");
        var recent = pipeline(ContinuationBaselinePipeline.Mode.RECENT_WHOLE_TURNS, null);
        var result = recent.compact(new CompactionRequest(List.of(Message.system("stable kernel"), Message.user("x".repeat(4_000))), 0, 1));
        assertThat(result.audit().failureCode()).isEqualTo("BASELINE_RECENT_WINDOW_OVER_BUDGET");
        assertThat(result.messages()).anyMatch(m -> m.role() == Role.USER && m.content().length() == 4_000);
    }

    @Test void currentModeDelegatesExactlyToTheProductionPipeline() {
        var tokenizer = new CharFallbackTokenizer();
        var budget = ContextBudgetPolicy.of(400);
        var analyzer = new ContextBudgetAnalyzer(tokenizer, budget);
        var production = new DefaultContextPipeline(new LadderedCompactor(null, tokenizer), analyzer, tokenizer, budget);
        var baseline = new ContinuationBaselinePipeline(production, analyzer, budget,
            ContinuationBaselinePipeline.Mode.CURRENT, 2, null, null);
        var request = new CompactionRequest(List.of(Message.user("short")), 0, 1);
        var expected = production.compact(request);
        var actual = baseline.compact(request);
        assertThat(actual.messages()).isEqualTo(expected.messages());
        assertThat(actual.audit().level()).isEqualTo(expected.audit().level());
    }

    @Test void invalidInputCannotBeRepairedBySilentlyDroppingAnOrphanToolResult() {
        var input = new ArrayList<>(history(6));
        input.add(1, Message.toolResult("unknown", "old orphan tool result"));
        var result = pipeline(ContinuationBaselinePipeline.Mode.RECENT_WHOLE_TURNS, null)
            .compact(new CompactionRequest(input, 0, 6));
        assertThat(result.audit().failureCode()).isEqualTo("BASELINE_INPUT_TOOL_PROTOCOL_INVALID");
        assertThat(result.messages()).isEqualTo(input);
    }

    private static ContinuationBaselinePipeline pipeline(ContinuationBaselinePipeline.Mode mode, ProviderGateway gateway) {
        var tokenizer = new CharFallbackTokenizer();
        var budget = ContextBudgetPolicy.of(400);
        var analyzer = new ContextBudgetAnalyzer(tokenizer, budget);
        return new ContinuationBaselinePipeline(new DefaultContextPipeline(new LadderedCompactor(null, tokenizer), analyzer, tokenizer, budget),
            analyzer, budget, mode, 2, gateway, new RunScope("summary", null, 1, RunPhase.COMPACT, ExecutionMode.REACT));
    }
    private static ProviderGateway gateway(List<ModelRequest> requests, String summary) {
        return new ProviderGateway() {
            @Override public ModelResponse generate(ModelRequest r, RunScope s) { requests.add(r); return ModelResponse.text(summary, TokenUsage.EMPTY); }
            @Override public ModelResponse generateStream(ModelRequest r, RunScope s, StreamObserver o) { return generate(r, s); }
        };
    }
    private static List<Message> history(int turns) {
        var history = new ArrayList<Message>();
        history.add(Message.system("stable kernel"));
        for (int i = 1; i <= turns; i++) {
            history.add(Message.user("question " + i + " " + "u".repeat(160)));
            history.add(Message.assistantWithTools(List.of(new ToolCall("call-" + i, "read", EvaluationArtifacts.JSON.createObjectNode().put("path", "sample.txt")))));
            history.add(Message.toolResult("call-" + i, "result " + "t".repeat(300)));
        }
        return history;
    }
}
