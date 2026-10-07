package com.clawkit.evaluation.context;

import com.clawkit.engine.*;
import com.clawkit.provider.*;
import com.clawkit.reliability.*;
import com.clawkit.tools.control.ExecutionHaltedException;
import com.clawkit.tools.schema.Message;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ProviderRequestLimitGatewayTest {
    @Test void rejectsBeforeDispatchWhenTheConservativeInputAndOutputReserveDoesNotFit() {
        var requests = new ArrayList<ModelRequest>();
        var guard = new ProviderRequestLimitGateway(gateway(requests, TokenUsage.EMPTY), 1024, 1024, true);
        var control = CancellationTree.root(null, BudgetLedger.of(100));
        assertThatThrownBy(() -> guard.generate(request(), scope(control))).isInstanceOf(ExecutionHaltedException.class);
        assertThat(requests).isEmpty();
    }

    @Test void capsOutputAndDisablesReasoningInTheActualDispatchedRequest() {
        var requests = new ArrayList<ModelRequest>();
        var guard = new ProviderRequestLimitGateway(gateway(requests, new TokenUsage(10, 5, 15)), 1024, 1024, true);
        guard.generate(new ModelRequest(List.of(Message.user("synthetic")), List.of(), new ModelParameters(0.0, 4096, false)),
            scope(CancellationTree.unbounded()));
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().parameters().maxTokens()).isEqualTo(1024);
        assertThat(requests.getFirst().parameters().reasoningMode()).isEqualTo(ProviderReasoningMode.DISABLED);
    }

    @Test void unavailableUsageDoesNotBecomeAZeroCostSuccessfulTrial() {
        var requests = new ArrayList<ModelRequest>();
        var guard = new ProviderRequestLimitGateway(gateway(requests, TokenUsage.EMPTY), 1024, 1024, true);
        assertThatThrownBy(() -> guard.generate(request(), scope(CancellationTree.unbounded())))
            .isInstanceOfSatisfying(LLMException.class, failure -> {
                assertThat(failure.getMessage()).isEqualTo("EVALUATION_USAGE_UNAVAILABLE");
                assertThat(failure.rejectedResponse()).isNotNull();
                assertThat(failure.rejectedResponse().usage().source()).isEqualTo(UsageSource.UNAVAILABLE);
            });
        assertThat(guard.accountingInvalid()).isTrue();
        assertThatThrownBy(() -> guard.generate(request(), scope(CancellationTree.unbounded())))
            .isInstanceOf(LLMException.class).hasMessage("EVALUATION_ACCOUNTING_UNKNOWN_STOP");
        assertThat(requests).hasSize(1);
    }

    private static ModelRequest request() { return ModelRequest.of(List.of(Message.user("synthetic")), List.of()); }
    private static RunScope scope(CancellationTree control) { return new RunScope("trial", null, 1, RunPhase.REACT, ExecutionMode.REACT, control); }
    private static ProviderGateway gateway(List<ModelRequest> requests, TokenUsage usage) {
        return new ProviderGateway() {
            @Override public ModelResponse generate(ModelRequest request, RunScope scope) { requests.add(request); return ModelResponse.text("received", usage); }
            @Override public ModelResponse generateStream(ModelRequest request, RunScope scope, StreamObserver observer) { return generate(request, scope); }
        };
    }
}
