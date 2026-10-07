package com.clawkit.evaluation.context;

import com.clawkit.engine.ProviderGateway;
import com.clawkit.engine.RunScope;
import com.clawkit.provider.*;
import com.clawkit.tools.control.ExecutionHaltedException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Live-evaluation guard: fixed output cap, conservative input reserve, serialized dispatch per instance. */
public final class ProviderRequestLimitGateway implements ProviderGateway {
    private final ProviderGateway delegate;
    private final int outputTokens;
    private final int framingReserveTokens;
    private final boolean requireActualUsage;
    private boolean accountingInvalid;

    public synchronized boolean accountingInvalid() { return accountingInvalid; }

    public ProviderRequestLimitGateway(ProviderGateway delegate, int outputTokens, int framingReserveTokens,
                                       boolean requireActualUsage) {
        if (outputTokens < 1 || framingReserveTokens < 1) throw new IllegalArgumentException("positive request limits required");
        this.delegate = delegate;
        this.outputTokens = outputTokens;
        this.framingReserveTokens = framingReserveTokens;
        this.requireActualUsage = requireActualUsage;
    }

    @Override public synchronized ModelResponse generate(ModelRequest request, RunScope scope) {
        if (accountingInvalid) throw new LLMException("EVALUATION_ACCOUNTING_UNKNOWN_STOP");
        ModelRequest bounded = bounded(request);
        long upper = conservativeReserve(bounded);
        scope.control().checkpoint();
        if (scope.control().tokenBudget().remaining() < upper) {
            throw new ExecutionHaltedException(ExecutionHaltedException.Reason.BUDGET_EXHAUSTED,
                "insufficient conservative request reserve: " + upper);
        }
        ModelResponse response;
        try { response = delegate.generate(bounded, scope); }
        catch (LLMException failure) {
            if (requireActualUsage && (failure.rejectedResponse() == null
                || failure.rejectedResponse().usage().source() != UsageSource.ACTUAL)) accountingInvalid = true;
            throw failure;
        }
        if (requireActualUsage && response.usage().source() != UsageSource.ACTUAL) {
            accountingInvalid = true;
            throw receivedButInvalid("EVALUATION_USAGE_UNAVAILABLE", response);
        }
        if (response.usage().source() == UsageSource.ACTUAL && response.usage().totalTokens() > upper) {
            accountingInvalid = true;
            throw receivedButInvalid("EVALUATION_USAGE_EXCEEDED_CONSERVATIVE_RESERVE", response);
        }
        return response;
    }

    @Override public ModelResponse generateStream(ModelRequest request, RunScope scope, StreamObserver observer) {
        throw new UnsupportedOperationException("frozen continuation evaluation uses blocking responses");
    }

    private ModelRequest bounded(ModelRequest request) {
        var parameters = request.parameters();
        int max = parameters.maxTokens() != null && parameters.maxTokens() > 0
            ? Math.min(outputTokens, parameters.maxTokens()) : outputTokens;
        return new ModelRequest(request.messages(), request.tools(), new ModelParameters(0.0, max,
            false, ProviderReasoningMode.DISABLED), request.control());
    }

    public long conservativeReserve(ModelRequest request) {
        try {
            byte[] input = EvaluationArtifacts.JSON.writeValueAsBytes(Map.of("messages", request.messages(), "tools", request.tools()));
            // UTF-8 bytes are an intentionally loose proxy; the explicit framing allowance remains a declared assumption.
            return (long) input.length + framingReserveTokens + outputTokens;
        } catch (Exception e) { throw new IllegalStateException("request cannot be frozen", e); }
    }

    private static LLMException receivedButInvalid(String code, ModelResponse response) {
        try {
            return new LLMException(code, null, null, 0, RejectedModelResponse.bounded("evaluation-limit",
                new String(EvaluationArtifacts.JSON.writeValueAsBytes(response), StandardCharsets.UTF_8), response.usage()));
        } catch (Exception e) { return new LLMException(code, e); }
    }
}
