package com.clawkit.evaluation.context;

import com.clawkit.engine.*;
import com.clawkit.engine.impl.ObservingProviderGateway;
import com.clawkit.observability.CompositeRunRecorder;
import com.clawkit.provider.*;
import com.clawkit.reliability.*;
import com.clawkit.tools.control.ExecutionHaltedException;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolDefinition;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class EvaluationBudgetTest {
    @Test void freshVerificationRootCannotResetTheInstanceCallBudget() {
        var calls = new AtomicInteger();
        var shared = CancellationTree.root(Instant.now().plusSeconds(10), BudgetLedger.of(5_000), WorkBudgetLedger.of(1, 10));
        var gateway = new BudgetedEvaluationGateway(new ObservingProviderGateway(provider(calls), new CompositeRunRecorder()), shared);
        gateway.generate(request(), scope("ingestion", RunPhase.MEMORY_EXTRACT, CancellationTree.unbounded()));
        assertThatThrownBy(() -> gateway.generate(request(), scope("new-verification-root", RunPhase.REACT, CancellationTree.unbounded())))
            .isInstanceOf(ExecutionHaltedException.class);
        assertThat(calls).hasValue(1);
        assertThat(shared.tokenBudget().remaining()).isEqualTo(4_985);
    }

    @Test void preservesLocalCancellationAndSettlesBothBudgetsOnce() {
        var calls = new AtomicInteger();
        var shared = CancellationTree.root(null, BudgetLedger.of(5_000), WorkBudgetLedger.of(4, 10));
        var local = CancellationTree.root(null, BudgetLedger.of(4_000), WorkBudgetLedger.of(2, 10));
        var gateway = new BudgetedEvaluationGateway(new ObservingProviderGateway(provider(calls), new CompositeRunRecorder()), shared);
        gateway.generate(request(), scope("task", RunPhase.REACT, local));
        assertThat(shared.tokenBudget().remaining()).isEqualTo(4_985);
        assertThat(local.tokenBudget().remaining()).isEqualTo(3_985);
        local.cancel();
        assertThatThrownBy(() -> gateway.generate(request(), scope("cancelled", RunPhase.COMPACT, local)))
            .isInstanceOf(ExecutionHaltedException.class);
        assertThat(calls).hasValue(1);
    }

    @Test void failedPaidResponseSettlesSharedTokensAndCannotDisappear() {
        var shared = CancellationTree.root(null, BudgetLedger.of(5_000), WorkBudgetLedger.of(4, 10));
        LLMProvider failed = new LLMProvider() {
            @Override public Message generate(List<Message> m, List<ToolDefinition> t) { throw new UnsupportedOperationException(); }
            @Override public ModelResponse generate(ModelRequest r) {
                throw new LLMException("received but invalid", null, null, 0,
                    RejectedModelResponse.bounded("parse", "invalid JSON", new TokenUsage(100, 20, 120)));
            }
        };
        var gateway = new BudgetedEvaluationGateway(new ObservingProviderGateway(failed, new CompositeRunRecorder()), shared);
        assertThatThrownBy(() -> gateway.generate(request(), scope("invalid", RunPhase.REACT, CancellationTree.unbounded())))
            .isInstanceOf(LLMException.class);
        assertThat(shared.tokenBudget().remaining()).isEqualTo(4_880);
    }

    private static RunScope scope(String id, RunPhase phase, CancellationTree local) {
        return new RunScope(id, null, 1, phase, ExecutionMode.REACT, local);
    }
    private static ModelRequest request() { return ModelRequest.of(List.of(Message.user("public fixture")), List.of()); }
    private static LLMProvider provider(AtomicInteger calls) {
        return new LLMProvider() {
            @Override public Message generate(List<Message> m, List<ToolDefinition> t) { throw new UnsupportedOperationException(); }
            @Override public ModelResponse generate(ModelRequest r) {
                calls.incrementAndGet();
                return ModelResponse.text("done", new TokenUsage(10, 5, 15));
            }
        };
    }
}
