package com.clawkit.ops.loop.automation;

import java.time.Instant;

/**
 * Per-target automation status snapshot.
 *
 * <p>Used both as the persistent state record and as a query result.
 * Counters are monotonically increasing within a single coordinator
 * lifecycle; after restart they reflect the restored state.
 */
public record AutomationStatus(
    String targetId,
    boolean paused,
    long requested,
    long started,
    long completed,
    long skipped,
    long merged,
    long failed,
    String lastRunId,
    String lastOutcome,
    String lastSkipReason,
    String inFlightRunId,
    Instant updatedAt
) {
    public AutomationStatus {
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt must not be null");
    }

    /** Create an initial status for a new target. */
    public static AutomationStatus initial(String targetId, Instant now) {
        return new AutomationStatus(targetId, false, 0, 0, 0, 0, 0, 0,
            null, null, null, null, now);
    }

    /** Copy with counters incremented. */
    public AutomationStatus withRequested(Instant now) {
        return new AutomationStatus(targetId, paused, requested + 1,
            started, completed, skipped, merged, failed,
            lastRunId, lastOutcome, lastSkipReason, inFlightRunId, now);
    }

    public AutomationStatus withStarted(String runId, Instant now) {
        return new AutomationStatus(targetId, paused, requested,
            started + 1, completed, skipped, merged, failed,
            runId, null, null, runId, now);
    }

    public AutomationStatus withCompleted(String runId, String outcome, Instant now) {
        return new AutomationStatus(targetId, paused, requested, started,
            completed + 1, skipped, merged, failed,
            runId, outcome, null, null, now);
    }

    public AutomationStatus withSkipped(String reason, String runId, Instant now) {
        return new AutomationStatus(targetId, paused, requested, started,
            completed, skipped + 1, merged, failed,
            runId, null, reason, null, now);
    }

    public AutomationStatus withMerged(String runId, Instant now) {
        return new AutomationStatus(targetId, paused, requested, started,
            completed, skipped, merged + 1, failed,
            runId, "MERGED", null, null, now);
    }

    public AutomationStatus withFailed(String runId, Instant now) {
        return new AutomationStatus(targetId, paused, requested, started,
            completed, skipped, merged, failed + 1,
            runId, "FAILED", null, null, now);
    }

    public AutomationStatus withPaused(boolean paused, Instant now) {
        return new AutomationStatus(targetId, paused, requested, started,
            completed, skipped, merged, failed,
            lastRunId, lastOutcome, lastSkipReason, inFlightRunId, now);
    }

    /** Mark an in-flight run as abandoned. */
    public AutomationStatus withAbandoned(String abandonedRunId, Instant now) {
        return new AutomationStatus(targetId, paused, requested, started,
            completed, skipped, merged, failed + 1,
            abandonedRunId, "ABANDONED_AFTER_RESTART", lastSkipReason, null, now);
    }
}
