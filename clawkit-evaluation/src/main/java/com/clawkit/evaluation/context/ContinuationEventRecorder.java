package com.clawkit.evaluation.context;

import com.clawkit.observability.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Captures the actual task root: preparatory provider-only roots are not task runs. */
public final class ContinuationEventRecorder implements RunRecorder {
    public record Captured(RunEventPayload payload, String runId, String parentRunId,
                           Integer turnNumber, Instant occurredAt) {}
    private final RunRecorder delegate;
    private final EvaluationArtifacts artifacts;
    private final String prefix;
    private final RunEventCodec codec = new RunEventCodec(EvaluationArtifacts.JSON);
    private final Map<String, Long> sequences = new HashMap<>();
    private final CopyOnWriteArrayList<Captured> events = new CopyOnWriteArrayList<>();
    private final Set<String> ids = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<String> orderedIds = new CopyOnWriteArrayList<>();
    private volatile String taskRoot;

    public ContinuationEventRecorder(RunRecorder delegate) { this(delegate, null, null); }
    public ContinuationEventRecorder(RunRecorder delegate, EvaluationArtifacts artifacts, String prefix) {
        this.delegate = delegate; this.artifacts = artifacts; this.prefix = prefix;
    }
    @Override public synchronized void record(RunEventPayload payload, String runId, String parentRunId,
                                              Integer turnNumber, Instant occurredAt) {
        // FileRunRecorder saves lifecycle runs only; ingestion and summaries also have provider-only scopes.
        if (payload instanceof RunStartedPayload && ids.add(runId)) orderedIds.add(runId);
        if (taskRoot == null && payload instanceof RunStartedPayload && parentRunId == null) taskRoot = runId;
        events.add(new Captured(payload, runId, parentRunId, turnNumber, occurredAt));
        if (artifacts != null) {
            long sequence = sequences.merge(runId, 1L, Long::sum);
            try {
                artifacts.append(prefix + "/runtime-events.jsonl", codec.encode(payload, runId, parentRunId,
                    turnNumber, sequence, occurredAt));
            } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        }
        delegate.record(payload, runId, parentRunId, turnNumber, occurredAt);
    }
    public String taskRoot() { return taskRoot; }
    public List<String> runIds() { return List.copyOf(orderedIds); }
    public List<Captured> captured() { return List.copyOf(events); }
    public long dispatchedProviderCalls() { return events.stream().filter(e -> e.payload() instanceof ProviderCallStartedPayload).count(); }
    public String taskStatus() {
        if (taskRoot == null) return "NOT_STARTED";
        return events.stream().filter(e -> e.runId().equals(taskRoot) && e.payload() instanceof RunCompletedPayload)
            .map(e -> ((RunCompletedPayload) e.payload()).status().name()).reduce((a, b) -> b).orElse("INCOMPLETE");
    }
}
