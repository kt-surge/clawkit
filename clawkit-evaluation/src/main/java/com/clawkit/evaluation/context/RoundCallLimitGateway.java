package com.clawkit.evaluation.context;

import com.clawkit.engine.ProviderGateway;
import com.clawkit.engine.RunScope;
import com.clawkit.provider.*;
import com.clawkit.tools.control.ExecutionHaltedException;
import com.clawkit.tools.control.WorkBudget;

/** Round request permits do not introduce a second token reservation over a child-capped shared pool. */
final class RoundCallLimitGateway implements ProviderGateway {
    private final ProviderGateway delegate;
    private final WorkBudget round;
    RoundCallLimitGateway(ProviderGateway delegate, WorkBudget round) { this.delegate = delegate; this.round = round; }
    @Override public ModelResponse generate(ModelRequest request, RunScope scope) {
        scope.control().checkpoint();
        if (!round.tryAcquireProviderCall()) throw new ExecutionHaltedException(ExecutionHaltedException.Reason.BUDGET_EXHAUSTED,
            "development round provider-call limit reached");
        return delegate.generate(request, scope);
    }
    @Override public ModelResponse generateStream(ModelRequest r, RunScope s, StreamObserver o) {
        throw new UnsupportedOperationException("continuation evaluation uses blocking responses");
    }
}
