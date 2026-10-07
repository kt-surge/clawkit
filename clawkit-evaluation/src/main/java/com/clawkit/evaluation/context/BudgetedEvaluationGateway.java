package com.clawkit.evaluation.context;

import com.clawkit.engine.ProviderGateway;
import com.clawkit.engine.RunScope;
import com.clawkit.provider.ModelRequest;
import com.clawkit.provider.ModelResponse;
import com.clawkit.provider.StreamObserver;
import com.clawkit.tools.control.*;
import java.time.Instant;
import java.util.Optional;

/** Adds a whole-instance cap across ingestion, summaries, execution, and independent verification roots. */
public final class BudgetedEvaluationGateway implements ProviderGateway {
    private final ProviderGateway delegate;
    private final ExecutionControl instance;

    public BudgetedEvaluationGateway(ProviderGateway delegate, ExecutionControl instance) {
        this.delegate = delegate;
        this.instance = instance;
    }

    @Override public ModelResponse generate(ModelRequest request, RunScope scope) {
        return delegate.generate(request, bounded(scope));
    }
    @Override public ModelResponse generateStream(ModelRequest request, RunScope scope, StreamObserver observer) {
        return delegate.generateStream(request, bounded(scope), observer);
    }
    private RunScope bounded(RunScope scope) {
        return new RunScope(scope.runId(), scope.parentRunId(), scope.turn(), scope.phase(), scope.executionMode(),
            new CombinedControl(scope.control(), instance));
    }

    private record CombinedControl(ExecutionControl local, ExecutionControl instance) implements ExecutionControl {
        @Override public boolean isCancelled() { return local.isCancelled() || instance.isCancelled(); }
        @Override public Optional<Instant> deadline() {
            return java.util.stream.Stream.concat(local.deadline().stream(), instance.deadline().stream()).min(Instant::compareTo);
        }
        @Override public TokenBudget tokenBudget() {
            return combine(local.tokenBudget(), instance.tokenBudget());
        }
        @Override public WorkBudget workBudget() {
            WorkBudget a = local.workBudget(), b = instance.workBudget();
            if (a == b) return a;
            return new WorkBudget() {
                @Override public boolean tryAcquireProviderCall() { return b.tryAcquireProviderCall() && a.tryAcquireProviderCall(); }
                @Override public boolean tryAcquireToolCall() { return b.tryAcquireToolCall() && a.tryAcquireToolCall(); }
                @Override public long remainingProviderCalls() { return Math.min(a.remainingProviderCalls(), b.remainingProviderCalls()); }
                @Override public long remainingToolCalls() { return Math.min(a.remainingToolCalls(), b.remainingToolCalls()); }
            };
        }
        @Override public CancelRegistration onCancel(Runnable action) {
            var once = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable callback = () -> { if (once.compareAndSet(false, true)) action.run(); };
            var a = local.onCancel(callback);
            var b = instance.onCancel(callback);
            return () -> { a.close(); b.close(); };
        }
    }

    private static TokenBudget combine(TokenBudget a, TokenBudget b) {
        if (a == b) return a;
        return new TokenBudget() {
            @Override public boolean limited() { return a.limited() || b.limited(); }
            @Override public long remaining() { return Math.min(a.remaining(), b.remaining()); }
            @Override public long reserveUpTo(long requested) {
                long first = b.reserveUpTo(requested);
                long second = a.reserveUpTo(first);
                if (second < first) b.settle(first - second, 0);
                return second;
            }
            @Override public void settle(long reserved, long actual) { a.settle(reserved, actual); b.settle(reserved, actual); }
        };
    }
}
