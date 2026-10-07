package com.clawkit.evaluation.context;

import com.clawkit.context.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicInteger;

/** Records the exact production build/compact boundary, including source/lifecycle/token estimates. */
public final class RecordingContextPipeline implements ContextPipeline {
    private final ContextPipeline delegate;
    private final EvaluationArtifacts artifacts;
    private final String prefix;
    private final AtomicInteger builds = new AtomicInteger();
    private final AtomicInteger compactions = new AtomicInteger();

    public RecordingContextPipeline(ContextPipeline delegate, EvaluationArtifacts artifacts, String prefix) {
        this.delegate = delegate;
        this.artifacts = artifacts;
        this.prefix = prefix;
    }

    @Override public ModelContext build(ContextRequest request) {
        var context = delegate.build(request);
        int index = builds.incrementAndGet();
        write("build-" + index, context);
        var slices = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var fragment : context.fragments()) {
            for (var message : fragment.messages()) {
                String kind = fragment.source().name();
                String text = message.content() == null ? "" : message.content();
                if (fragment.source() == ContextSource.MEMORY) {
                    if (text.startsWith("[Working Memory]")) kind = "WORKING_MEMORY";
                    else if (text.startsWith("[Related Past Sessions]")) kind = "HISTORICAL_SESSION_SUMMARY";
                    else if (text.startsWith("[Relevant Memory:")) kind = "LONG_TERM_MEMORY";
                }
                slices.add(java.util.Map.of("kind", kind, "lifecycle", fragment.lifecycle().name(),
                    "fragmentId", fragment.id(), "message", message, "tokenCountKind", "CONTEXT_ESTIMATE",
                    "buildIndex", index));
            }
        }
        write("build-" + index + "-sources", slices);
        return context;
    }

    @Override public CompactionResult compact(CompactionRequest request) {
        var result = delegate.compact(request);
        int index = compactions.incrementAndGet();
        write("compact-" + index + "-input", request);
        write("compact-" + index + "-result", result);
        return result;
    }

    private void write(String name, Object value) {
        try { artifacts.write(prefix + "/context/" + name + ".json", value); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
