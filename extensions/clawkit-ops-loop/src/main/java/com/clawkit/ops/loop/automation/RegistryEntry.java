package com.clawkit.ops.loop.automation;

import java.time.Instant;
import java.util.List;

/**
 * A single entry in the {@link IncidentRegistry}.
 *
 * <p>Each entry links a fingerprint to an incident and tracks observation
 * history. Status drives deduplication: only one ACTIVE entry per fingerprint.
 *
 * @param fingerprint       the stable incident fingerprint
 * @param incidentId        the incident this entry belongs to
 * @param status            ACTIVE, MERGED, or CLOSED
 * @param firstObservedAt   when this fingerprint was first observed
 * @param lastObservedAt    when this fingerprint was most recently observed
 * @param observationCount  total number of observations with this fingerprint
 * @param lastRunId         the run that produced the most recent observation
 * @param lastEvidenceRefs  evidence references from the most recent observation
 */
public record RegistryEntry(
    String fingerprint,
    String incidentId,
    EntryStatus status,
    Instant firstObservedAt,
    Instant lastObservedAt,
    long observationCount,
    String lastRunId,
    List<String> lastEvidenceRefs
) {
    public RegistryEntry {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint must not be blank");
        }
        if (incidentId == null || incidentId.isBlank()) {
            throw new IllegalArgumentException("incidentId must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (firstObservedAt == null) {
            throw new IllegalArgumentException("firstObservedAt must not be null");
        }
        if (lastObservedAt == null) {
            throw new IllegalArgumentException("lastObservedAt must not be null");
        }
        if (observationCount < 1) {
            throw new IllegalArgumentException("observationCount must be >= 1");
        }
        lastEvidenceRefs = lastEvidenceRefs == null
            ? List.of() : List.copyOf(lastEvidenceRefs);
    }

    public enum EntryStatus {
        /** The incident is currently active — further observations merge here. */
        ACTIVE,
        /** Another observation was merged into an existing ACTIVE entry. */
        MERGED,
        /** The incident has been closed (resolved, escalated, or otherwise ended). */
        CLOSED
    }

    /** Create a new ACTIVE entry for the first observation. */
    public static RegistryEntry create(
        IncidentFingerprint fingerprint,
        String incidentId,
        Instant observedAt,
        String runId,
        List<String> evidenceRefs
    ) {
        return new RegistryEntry(
            fingerprint.hash(), incidentId, EntryStatus.ACTIVE,
            observedAt, observedAt, 1, runId, evidenceRefs);
    }

    /** Create a MERGED entry recording that an observation was merged. */
    public static RegistryEntry merged(
        IncidentFingerprint fingerprint,
        String mergedIntoIncidentId,
        Instant observedAt,
        String runId,
        List<String> evidenceRefs
    ) {
        return new RegistryEntry(
            fingerprint.hash(), mergedIntoIncidentId, EntryStatus.MERGED,
            observedAt, observedAt, 1, runId, evidenceRefs);
    }

    /** Return a copy with lastObservedAt bumped and observationCount incremented. */
    public RegistryEntry withObservation(Instant observedAt, String runId,
                                         List<String> evidenceRefs) {
        return new RegistryEntry(
            fingerprint, incidentId, status, firstObservedAt,
            observedAt, observationCount + 1, runId, evidenceRefs);
    }

    /** Return a copy with status changed to CLOSED. */
    public RegistryEntry closed(Instant closedAt) {
        return new RegistryEntry(
            fingerprint, incidentId, EntryStatus.CLOSED,
            firstObservedAt, closedAt, observationCount, lastRunId, lastEvidenceRefs);
    }
}
