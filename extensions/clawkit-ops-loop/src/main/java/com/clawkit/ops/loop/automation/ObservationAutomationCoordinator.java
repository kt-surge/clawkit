package com.clawkit.ops.loop.automation;

import com.clawkit.ops.loop.DiscoveryResult;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates per-target observe-only automation.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Scheduling and lifecycle (via {@link AutomationTaskScheduler})</li>
 *   <li>Per-target active guard — at most one observation per target at a time</li>
 *   <li>Global and per-target pause/resume (persisted)</li>
 *   <li>Discovery and provider budget enforcement</li>
 *   <li>Delegation to {@link ObservationRunner}, {@link ObservationToIncidentBridge},
 *       and {@link DiagnosisRunner}</li>
 *   <li>Outcome recording via {@link AutomationStateStore}</li>
 * </ul>
 *
 * <p>This coordinator never calls FixSession, OpsFixSession, RepairOrchestrator,
 * opsfix, restart_service, or any write-capable tool.
 */
public final class ObservationAutomationCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ObservationAutomationCoordinator.class);
    private static final Duration CLOSE_DRAIN_TIMEOUT = Duration.ofSeconds(5);

    private final AutomationTaskScheduler scheduler;
    private final AutomationStateStore stateStore;
    private final ObservationRunner observationRunner;
    private final ObservationToIncidentBridge bridge;
    private final DiagnosisRunner diagnosisRunner;
    private final Clock clock;
    private final String serviceId;
    private final Duration scheduleDelay;
    private final boolean diagnosisEnabled;
    private final ObservationEventSink eventSink;

    // Per-target guard: targetId -> true if currently observing
    private final Set<String> activeTargets = ConcurrentHashMap.newKeySet();
    // Per-target scheduled handle
    private final Map<String, ScheduledHandle> handles = new ConcurrentHashMap<>();
    // Initial delay before first observation
    private final Duration initialDelay;
    private boolean started;
    private boolean closed;

    public ObservationAutomationCoordinator(
        AutomationTaskScheduler scheduler,
        AutomationStateStore stateStore,
        ObservationRunner observationRunner,
        ObservationToIncidentBridge bridge,
        DiagnosisRunner diagnosisRunner,
        Clock clock,
        String serviceId,
        Duration initialDelay,
        Duration scheduleDelay
    ) {
        this(scheduler, stateStore, observationRunner, bridge, diagnosisRunner, clock,
            serviceId, initialDelay, scheduleDelay, true, ObservationEventSink.NOOP);
    }

    /**
     * Create a coordinator with an explicit diagnosis policy.
     *
     * <p>Observe-only Fixture compositions pass {@code false}: their A0 loop
     * ends after deterministic observation and incident registration, without
     * consuming a Provider budget or attempting a model call.
     */
    public ObservationAutomationCoordinator(
        AutomationTaskScheduler scheduler,
        AutomationStateStore stateStore,
        ObservationRunner observationRunner,
        ObservationToIncidentBridge bridge,
        DiagnosisRunner diagnosisRunner,
        Clock clock,
        String serviceId,
        Duration initialDelay,
        Duration scheduleDelay,
        boolean diagnosisEnabled
    ) {
        this(scheduler, stateStore, observationRunner, bridge, diagnosisRunner, clock,
            serviceId, initialDelay, scheduleDelay, diagnosisEnabled, ObservationEventSink.NOOP);
    }

    /** Create a coordinator with an explicit diagnosis policy and event projection. */
    public ObservationAutomationCoordinator(
        AutomationTaskScheduler scheduler,
        AutomationStateStore stateStore,
        ObservationRunner observationRunner,
        ObservationToIncidentBridge bridge,
        DiagnosisRunner diagnosisRunner,
        Clock clock,
        String serviceId,
        Duration initialDelay,
        Duration scheduleDelay,
        boolean diagnosisEnabled,
        ObservationEventSink eventSink
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.observationRunner = Objects.requireNonNull(observationRunner, "observationRunner");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.diagnosisRunner = Objects.requireNonNull(diagnosisRunner, "diagnosisRunner");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
        this.serviceId = serviceId;
        this.initialDelay = Objects.requireNonNull(initialDelay, "initialDelay");
        this.scheduleDelay = Objects.requireNonNull(scheduleDelay, "scheduleDelay");
        this.diagnosisEnabled = diagnosisEnabled;
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must not be negative");
        }
        if (scheduleDelay.isNegative() || scheduleDelay.isZero()) {
            throw new IllegalArgumentException("scheduleDelay must be positive");
        }
    }

    // ── Startup / Recovery ────────────────────────────────────────

    /** Recover state and schedule targets that are not paused. */
    public synchronized void start() throws IOException {
        ensureOpen();
        if (started) return;
        int abandoned = stateStore.recoverAbandoned();
        if (abandoned > 0) {
            log.warn("Recovered {} abandoned in-flight runs", abandoned);
        }
        // Resume targets that are not paused — they'll be scheduled
        resumeUnpausedTargets();
        started = true;
    }

    private void resumeUnpausedTargets() throws IOException {
        // Targets that already exist in state and aren't paused get scheduled
        for (var ts : List.copyOf(stateStore.targets().entrySet())) {
            String targetId = ts.getKey();
            if (!ts.getValue().paused() && !stateStore.isGlobalPaused()) {
                scheduleTarget(targetId);
            }
        }
    }

    // ── Scheduling ────────────────────────────────────────────────

    /** Schedule a target for recurring observation. Idempotent. */
    public synchronized void scheduleTarget(String targetId) throws IOException {
        ensureOpen();
        stateStore.registerTarget(targetId);
        if (handles.containsKey(targetId)) return; // already scheduled

        if (stateStore.isGlobalPaused() || stateStore.isTargetPaused(targetId)) {
            log.debug("Target {} not scheduled: paused", targetId);
            return;
        }

        long delayMs = scheduleDelay.toMillis();
        long initialMs = initialDelay.toMillis();
        ScheduledHandle handle = scheduler.schedule(
            () -> executeObservation(targetId),
            initialMs, delayMs, TimeUnit.MILLISECONDS);
        handles.put(targetId, handle);
        log.info("Target {} scheduled: initialDelay={}ms delay={}ms",
            targetId, initialMs, delayMs);
    }

    // ── Pause / Resume ────────────────────────────────────────────

    public synchronized void pauseTarget(String targetId) throws IOException {
        ensureOpen();
        stateStore.setTargetPaused(targetId, true);
        cancelHandle(targetId);
        log.info("Target {} paused", targetId);
    }

    public synchronized void resumeTarget(String targetId) throws IOException {
        ensureOpen();
        stateStore.setTargetPaused(targetId, false);
        if (!stateStore.isGlobalPaused()) {
            scheduleTarget(targetId);
        }
        log.info("Target {} resumed", targetId);
    }

    public synchronized void pauseAll() throws IOException {
        ensureOpen();
        stateStore.setGlobalPaused(true);
        for (String targetId : Set.copyOf(handles.keySet())) {
            cancelHandle(targetId);
        }
        log.info("All targets paused (global)");
    }

    public synchronized void resumeAll() throws IOException {
        ensureOpen();
        stateStore.setGlobalPaused(false);
        for (var ts : List.copyOf(stateStore.targets().entrySet())) {
            if (!ts.getValue().paused()) {
                scheduleTarget(ts.getKey());
            }
        }
        log.info("All unpaused targets resumed");
    }

    public boolean isTargetPaused(String targetId) {
        return stateStore.isTargetPaused(targetId);
    }

    public boolean isGlobalPaused() {
        return stateStore.isGlobalPaused();
    }

    // ── Core execution loop ────────────────────────────────────────

    void executeObservation(String targetId) {
        // Close and the active-target admission share one monitor. A scheduler
        // callback that was queued just as close() began must not touch stores
        // after their owners have been released; a callback admitted before
        // close() is visible in activeTargets and is drained before release.
        boolean busy;
        synchronized (this) {
            if (closed) return;
            busy = !activeTargets.add(targetId);
        }
        // Fast process-local guard. Persistent in-flight state is checked again
        // by beginObservation so a restart or a second coordinator fails closed.
        if (busy) {
            try {
                stateStore.recordBusyTrigger(targetId);
            } catch (IOException e) {
                log.error("Failed to persist busy skip for {}", targetId, e);
            }
            return;
        }

        try {
            executeObservationGuarded(targetId);
        } finally {
            activeTargets.remove(targetId);
        }
    }

    private void executeObservationGuarded(String targetId) {
        String runId = "obs-" + targetId + "-"
            + UUID.randomUUID().toString().substring(0, 8);
        Instant startTime = clock.instant();
        boolean admitted = false;
        boolean terminal = false;

        try {
            // Pause checks, discovery budget, counters and in-flight marker are
            // one durable transaction. Any write failure stops before observe().
            AutomationStateStore.ObservationAdmission admission =
                stateStore.beginObservation(targetId, runId);
            if (admission != AutomationStateStore.ObservationAdmission.STARTED) {
                log.debug("[{}] Observation skipped: {}", runId, admission);
                return;
            }
            admitted = true;
            log.debug("[{}] Observation started", runId);

            DiscoveryResult discovery = observationRunner.observe(targetId);
            ObservationToIncidentBridge.BridgeResult bridgeResult =
                bridge.process(discovery, targetId, serviceId);

            String outcome;
            boolean providerCalled = false;

            switch (bridgeResult.outcome()) {
                case INCIDENT_CREATED -> {
                    outcome = "INCIDENT_CREATED";
                    stateStore.completeObservation(targetId, runId, outcome, false);
                    terminal = true;
                    if (diagnosisEnabled) {
                        providerCalled = tryDiagnosis(targetId, discovery, runId);
                    } else {
                        stateStore.recordDiagnosisSkip(
                            targetId, runId, "DIAGNOSIS_DISABLED_OBSERVE_ONLY");
                    }
                }
                case INCIDENT_MERGED -> {
                    outcome = "INCIDENT_MERGED";
                    stateStore.completeObservation(targetId, runId, outcome, true);
                    terminal = true;
                    // The registry has already identified an ACTIVE incident with the
                    // same deterministic fingerprint. Re-running model diagnosis here
                    // would turn a de-duplicated observation stream into repeated
                    // Provider spend without yielding a new decision point. A later
                    // policy can add evidence-change thresholds explicitly; the
                    // observe-only baseline diagnoses only newly created incidents.
                    stateStore.recordDiagnosisSkip(
                        targetId, runId, "SKIPPED_ACTIVE_INCIDENT_MERGED");
                }
                case COOLDOWN_SKIPPED -> {
                    outcome = "COOLDOWN_SKIPPED";
                    // Discovery completed and its evidence is retained. Only new incident/
                    // provider work is suppressed during the post-recovery cooldown.
                    stateStore.completeObservation(targetId, runId, outcome, false);
                    terminal = true;
                }
                case HEALTHY -> {
                    outcome = "HEALTHY";
                    stateStore.completeObservation(targetId, runId, outcome, false);
                    terminal = true;
                }
                case HEALTHY_RECOVERED -> {
                    outcome = "HEALTHY_RECOVERED";
                    stateStore.completeObservation(targetId, runId, outcome, false);
                    terminal = true;
                }
                case UNKNOWN -> {
                    outcome = "UNKNOWN";
                    stateStore.completeObservation(targetId, runId, outcome, false);
                    terminal = true;
                }
                case SKIPPED_TARGET_BUSY -> {
                    outcome = "SKIPPED_TARGET_BUSY";
                    stateStore.skipObservation(targetId, runId, outcome);
                    terminal = true;
                    recordObservation(targetId, discovery, outcome, false);
                    return;
                }
                default -> {
                    outcome = bridgeResult.outcome().name();
                    stateStore.completeObservation(targetId, runId, outcome, false);
                    terminal = true;
                }
            }

            Instant endTime = clock.instant();
            recordObservation(targetId, discovery, outcome, providerCalled);
            long durationMs = Duration.between(startTime, endTime).toMillis();
            log.info("[{}] Observation complete: outcome={} providerCalled={} duration={}ms",
                runId, outcome, providerCalled, durationMs);

        } catch (Exception e) {
            log.error("[{}] Unexpected error in observation cycle: {}", runId, e.getMessage(), e);
            if (admitted && !terminal) {
                try {
                    stateStore.failObservation(targetId, runId);
                } catch (Exception inner) {
                    log.error("[{}] Failed to persist terminal failure state: {}",
                        runId, inner.getMessage(), inner);
                }
            }
        }
    }

    // ── Diagnosis ──────────────────────────────────────────────────

    private boolean tryDiagnosis(String targetId, DiscoveryResult discovery, String runId) {
        try {
            if (!stateStore.tryConsumeProvider(targetId)) {
                stateStore.recordDiagnosisSkip(
                    targetId, runId, "SKIPPED_PROVIDER_BUDGET");
                log.debug("[{}] Provider budget exhausted — skipping diagnosis", runId);
                return false;
            }
        } catch (IOException e) {
            // Provider budget must be durably pre-committed. A state failure is
            // therefore a hard gate and must never fall through to diagnose().
            log.error("[{}] Provider admission state failed — diagnosis skipped: {}",
                runId, e.getMessage(), e);
            return false;
        }

        try {
            var diag = diagnosisRunner.diagnose(targetId, discovery);
            log.debug("[{}] Diagnosis completed", runId);
            return diag.providerCalled();
        } catch (Exception e) {
            log.error("[{}] Diagnosis failed (non-fatal): {}", runId, e.getMessage());
            try {
                stateStore.recordDiagnosisSkip(targetId, runId, "DIAGNOSIS_FAILED");
            } catch (IOException stateError) {
                log.error("[{}] Failed to persist diagnosis failure: {}",
                    runId, stateError.getMessage(), stateError);
            }
            return false;
        }
    }

    private void recordObservation(
        String targetId, DiscoveryResult discovery, String outcome, boolean providerCalled
    ) {
        List<String> refs = discovery.bundle() == null ? List.of()
            : discovery.bundle().evidence().stream()
                .map(com.clawkit.ops.loop.Evidence::rawReference)
                .filter(ref -> ref != null && !ref.isBlank())
                .toList();
        try {
            eventSink.record(new ObservationEventSink.ObservationEvent(
                targetId, discovery.runId(), outcome, refs, FixtureEvidenceFact.from(discovery),
                diagnosisEnabled, providerCalled));
        } catch (Exception e) {
            // Observability must not change the decision or cause a retry of a
            // completed observe-only cycle. The durable state/registry remains
            // authoritative; a failed projection is surfaced in logs only.
            log.warn("[{}] Failed to append observation event: {}", discovery.runId(), e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private void cancelHandle(String targetId) {
        ScheduledHandle handle = handles.remove(targetId);
        if (handle != null) {
            handle.cancel(false);
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("coordinator is closed");
    }

    public AutomationStatus getStatus(String targetId) {
        return stateStore.status(targetId);
    }

    public AutomationBudgetStatus getBudgetStatus(String targetId) {
        return stateStore.budgetStatus(targetId);
    }

    /**
     * Wait until observations that were already admitted have reached a terminal state.
     *
     * <p>Callers use this only after cancelling future schedules and before closing
     * the persistence resources used by the runners. It does not interrupt an
     * in-flight read-only observation.
     */
    public boolean awaitIdle(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!activeTargets.isEmpty()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            try {
                long millis = Math.max(1, Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), 20));
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** Snapshot of target state for testing. Package-private. */
    AutomationStateStore stateStore() { return stateStore; }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (String targetId : Set.copyOf(handles.keySet())) {
            cancelHandle(targetId);
        }
        scheduler.close();
        if (!awaitIdle(CLOSE_DRAIN_TIMEOUT)) {
            log.warn("Automation coordinator did not drain within {}; persistence owners must remain fail-closed",
                CLOSE_DRAIN_TIMEOUT);
        }
    }
}
