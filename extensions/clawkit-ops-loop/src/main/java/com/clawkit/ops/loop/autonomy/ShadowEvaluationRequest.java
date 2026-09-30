package com.clawkit.ops.loop.autonomy;

import com.clawkit.ops.loop.Diagnosis;
import com.clawkit.ops.loop.repair.RepairSuggestion;

import java.time.Instant;
import java.util.Objects;

/** Immutable inputs to an A3 decision. No repair session or executable action is accepted. */
public record ShadowEvaluationRequest(
    String incidentId,
    String targetId,
    String evidenceSnapshotHash,
    Instant evidenceObservedAt,
    Diagnosis diagnosis,
    RepairSuggestion suggestion,
    ModelOpinion modelOpinion
) {
    public ShadowEvaluationRequest {
        requireNonBlank(incidentId, "incidentId");
        requireNonBlank(targetId, "targetId");
        if (evidenceSnapshotHash == null || !evidenceSnapshotHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("evidenceSnapshotHash must be a SHA-256 hex value");
        }
        evidenceObservedAt = Objects.requireNonNull(evidenceObservedAt, "evidenceObservedAt");
        diagnosis = Objects.requireNonNull(diagnosis, "diagnosis");
        suggestion = Objects.requireNonNull(suggestion, "suggestion");
        modelOpinion = Objects.requireNonNull(modelOpinion, "modelOpinion");
        if (!incidentId.equals(suggestion.incidentId())) {
            throw new IllegalArgumentException("suggestion incidentId must match Shadow request");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
