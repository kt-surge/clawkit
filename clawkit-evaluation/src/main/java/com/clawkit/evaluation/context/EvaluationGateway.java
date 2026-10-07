package com.clawkit.evaluation.context;

import com.clawkit.engine.ProviderGateway;
import com.clawkit.engine.RunScope;
import com.clawkit.provider.ModelRequest;
import com.clawkit.provider.ModelResponse;
import com.clawkit.provider.StreamObserver;
import com.clawkit.provider.TokenUsage;
import com.clawkit.provider.LLMException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Evaluation opt-in transcript boundary. Captures every phase, including failures. */
public final class EvaluationGateway implements ProviderGateway {
    private final ProviderGateway delegate;
    private final EvaluationArtifacts artifacts;
    private final UsageLedger ledger;
    private final String prefix;
    private final AtomicInteger sequence = new AtomicInteger();

    public EvaluationGateway(ProviderGateway delegate, EvaluationArtifacts artifacts,
                             UsageLedger ledger, String prefix) {
        this.delegate = delegate;
        this.artifacts = artifacts;
        this.ledger = ledger;
        this.prefix = prefix;
    }

    @Override public ModelResponse generate(ModelRequest request, RunScope scope) {
        return call(request, scope, () -> delegate.generate(request, scope));
    }

    @Override public ModelResponse generateStream(ModelRequest request, RunScope scope, StreamObserver observer) {
        return call(request, scope, () -> delegate.generateStream(request, scope, observer));
    }

    private ModelResponse call(ModelRequest request, RunScope scope, java.util.function.Supplier<ModelResponse> operation) {
        String id = prefix.substring(prefix.lastIndexOf('/') + 1) + "-call-" + sequence.incrementAndGet();
        var input = Map.of("callId", id, "phase", scope.phase().name(), "runId", scope.runId(),
            "turn", scope.turn(), "messages", request.messages(), "tools", request.tools(), "parameters", request.parameters());
        persist(prefix + "/calls/" + id + "-request.json", input);
        long start = System.nanoTime();
        ModelResponse response;
        try {
            response = operation.get();
        } catch (RuntimeException failure) {
            TokenUsage usage = failure instanceof LLMException llm && llm.rejectedResponse() != null
                ? llm.rejectedResponse().usage() : TokenUsage.EMPTY;
            var entry = entry(id, scope, start, usage, failure.getClass().getSimpleName());
            ledger.add(entry);
            var rejected = new LinkedHashMap<String, Object>();
            rejected.put("usageEntry", entry);
            if (failure instanceof LLMException llm) {
                rejected.put("retryCount", llm.retryCount());
                rejected.put("rejectedResponse", llm.rejectedResponse());
            }
            persist(prefix + "/calls/" + id + "-response.json", rejected);
            throw failure;
        }
        var entry = entry(id, scope, start, response.usage(), null);
        ledger.add(entry);
        var output = new LinkedHashMap<String, Object>();
        output.put("usageEntry", entry);
        output.put("response", response);
        persist(prefix + "/calls/" + id + "-response.json", output);
        return response;
    }

    private UsageLedger.Entry entry(String id, RunScope scope, long started, TokenUsage usage, String failure) {
        return new UsageLedger.Entry(id, scope.phase().name(), failure == null ? "COMPLETED" : "FAILED",
            (System.nanoTime() - started) / 1_000_000, usage, failure);
    }

    private void persist(String file, Object value) {
        try { artifacts.write(file, value); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
