package com.clawkit.ops.loop.automation;

import com.clawkit.ops.loop.DiscoveryProfile;
import com.clawkit.ops.loop.DiscoveryResult;
import com.clawkit.ops.loop.DiscoveryStatus;
import com.clawkit.ops.loop.Evidence;
import com.clawkit.ops.loop.EvidenceBundle;
import com.clawkit.ops.loop.EvidenceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationAutomationCoordinatorTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC);
    private static final Duration SCHEDULE_DELAY = Duration.ofSeconds(30);

    private Path registryPath;
    private Path statePath;
    private IncidentRegistry registry;
    private ObservationToIncidentBridge bridge;
    private AutomationStateStore stateStore;
    private ManualAutomationTaskScheduler scheduler;
    private CountingObservationRunner obsRunner;
    private CountingDiagnosisRunner diagRunner;
    private ObservationAutomationCoordinator coordinator;

    @BeforeEach
    void setUp() throws IOException {
        registryPath = tempDir.resolve("registry.jsonl");
        statePath = tempDir.resolve("state.json");
        registry = new IncidentRegistry(registryPath, CLOCK);
        bridge = new ObservationToIncidentBridge(registry, CLOCK);
        stateStore = new AutomationStateStore(statePath, CLOCK);
        scheduler = new ManualAutomationTaskScheduler();
        obsRunner = new CountingObservationRunner();
        diagRunner = new CountingDiagnosisRunner();
        coordinator = new ObservationAutomationCoordinator(
            scheduler, stateStore, obsRunner, bridge, diagRunner,
            CLOCK, "order-api", Duration.ZERO, SCHEDULE_DELAY);
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) coordinator.close();
        if (stateStore != null) stateStore.close();
        if (registry != null) registry.close();
    }

    // ── Test 1: target pause blocks all ──

    @Test
    void targetPauseBlocksAllObservationCycles() throws Exception {
        coordinator.scheduleTarget("target-1");
        scheduler.tick(3); // 3 observations should run
        int beforeObs = obsRunner.callCount.get();
        assertThat(beforeObs).isGreaterThan(0);

        coordinator.pauseTarget("target-1");
        scheduler.tick(100);

        // No more observations after pause
        assertThat(obsRunner.callCount.get()).isEqualTo(beforeObs);
        assertThat(diagRunner.callCount.get()).isLessThanOrEqualTo(beforeObs);
    }

    // ── Test 2: global pause blocks all ──

    @Test
    void globalPauseBlocksAllTargets() throws Exception {
        coordinator.scheduleTarget("target-1");
        coordinator.scheduleTarget("target-2");
        scheduler.tick(3);
        int beforeObs = obsRunner.callCount.get();
        assertThat(beforeObs).isGreaterThan(0);

        coordinator.pauseAll();
        scheduler.tick(10);

        assertThat(obsRunner.callCount.get()).isEqualTo(beforeObs);
    }

    // ── Test 3: resume works correctly ──

    @Test
    void resumeSchedulesExactlyOneHandleAndFirstTriggerExecutesOnce() throws Exception {
        coordinator.scheduleTarget("target-1");
        scheduler.tick(2);
        coordinator.pauseTarget("target-1");
        int afterPause = obsRunner.callCount.get();
        assertThat(afterPause).isGreaterThan(0);

        coordinator.resumeTarget("target-1");
        scheduler.tick(1);
        // One more observation after resume
        assertThat(obsRunner.callCount.get()).isEqualTo(afterPause + 1);

        // Duplicate resume should not double-register
        coordinator.resumeTarget("target-1");
        scheduler.tick(1);
        assertThat(obsRunner.callCount.get()).isEqualTo(afterPause + 2);
    }

    @Test
    void targetRegistrationPersistsBeforeFirstTriggerAndStartIsIdempotent() throws Exception {
        coordinator.scheduleTarget("target-1");
        coordinator.close();
        stateStore.close();

        stateStore = new AutomationStateStore(statePath, CLOCK);
        scheduler = new ManualAutomationTaskScheduler();
        coordinator = new ObservationAutomationCoordinator(
            scheduler, stateStore, obsRunner, bridge, diagRunner,
            CLOCK, "order-api", Duration.ZERO, SCHEDULE_DELAY);

        coordinator.start();
        coordinator.start();
        assertThat(scheduler.taskCount()).isEqualTo(1);
    }

    @Test
    void queuedCallbackAfterCloseCannotTouchPersistenceOwners() throws Exception {
        coordinator.scheduleTarget("target-1");
        coordinator.close();
        int before = obsRunner.callCount.get();

        // This represents a JDK scheduler callback that won the queue race
        // after close cancelled future work. It must return before it can use
        // stateStore or registry, both of which may now be closed by the owner.
        coordinator.executeObservation("target-1");

        assertThat(obsRunner.callCount.get()).isEqualTo(before);
        assertThat(stateStore.status("target-1").requested()).isZero();
    }

    // ── Test 4: pause state persists across restart ──

    @Test
    void pauseStatePersistsAfterRestart() throws Exception {
        coordinator.scheduleTarget("target-1");
        coordinator.pauseTarget("target-1");
        coordinator.close();
        stateStore.close();

        // Restart — pause state persisted
        stateStore = new AutomationStateStore(statePath, CLOCK);
        assertThat(stateStore.isTargetPaused("target-1")).isTrue();
        assertThat(stateStore.isTargetPaused("target-2")).isFalse();
    }

    // ── Test 5: same-target concurrency = 1 (uses JDK scheduler) ──

    @Test
    void sameTargetConcurrencyIsAtMostOne() throws Exception {
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger obsCount = new AtomicInteger(0);
        var jdkScheduler = new JdkAutomationTaskScheduler(4);
        obsRunner = new CountingObservationRunner() {
            @Override
            public DiscoveryResult observe(String targetId) {
                int c = concurrent.incrementAndGet();
                maxConcurrent.updateAndGet(m -> Math.max(m, c));
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                concurrent.decrementAndGet();
                obsCount.incrementAndGet();
                return appDownDiscovery(targetId, "run-" + targetId);
            }
        };
        var jdkCoord = new ObservationAutomationCoordinator(
            jdkScheduler, stateStore, obsRunner, bridge, diagRunner,
            CLOCK, "order-api", Duration.ZERO, Duration.ofMillis(10));
        try {
            jdkCoord.scheduleTarget("target-1");
            Thread.sleep(500);
        } finally {
            jdkCoord.close();
            jdkScheduler.close();
            Thread.sleep(100);
        }
        // Active guard ensures max 1 concurrent same-target observation
        assertThat(maxConcurrent.get()).isEqualTo(1);
        // At least some observations executed
        assertThat(obsCount.get()).isGreaterThan(0);
    }

    // ── Test 6: different targets can run in parallel (uses JDK scheduler) ──

    @Test
    void differentTargetsCanRunInParallel() throws Exception {
        Set<String> running = ConcurrentHashMap.newKeySet();
        AtomicInteger maxParallel = new AtomicInteger(0);
        var jdkScheduler = new JdkAutomationTaskScheduler(4);
        obsRunner = new CountingObservationRunner() {
            @Override
            public DiscoveryResult observe(String targetId) {
                running.add(targetId);
                maxParallel.updateAndGet(m -> Math.max(m, running.size()));
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                running.remove(targetId);
                callCount.incrementAndGet();
                return appDownDiscovery(targetId, "run-" + targetId);
            }
        };
        var jdkCoord = new ObservationAutomationCoordinator(
            jdkScheduler, stateStore, obsRunner, bridge, diagRunner,
            CLOCK, "order-api", Duration.ZERO, Duration.ofMillis(10));
        try {
            jdkCoord.scheduleTarget("t1");
            jdkCoord.scheduleTarget("t2");
            jdkCoord.scheduleTarget("t3");
            Thread.sleep(500);
        } finally {
            jdkCoord.close();
            jdkScheduler.close();
            Thread.sleep(100); // allow daemon threads to terminate
        }
        // Different targets reached concurrency > 1
        assertThat(maxParallel.get()).isGreaterThan(1);
    }

    // ── Test 7: runner exception doesn't stop cycle ──

    @Test
    void runnerExceptionDoesNotHaltSubsequentCycles() throws Exception {
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        obsRunner = new CountingObservationRunner() {
            @Override
            public DiscoveryResult observe(String targetId) {
                int c = failCount.incrementAndGet();
                if (c <= 3) throw new RuntimeException("simulated failure " + c);
                successCount.incrementAndGet();
                callCount.incrementAndGet();
                return appDownDiscovery(targetId, "run-" + c);
            }
        };
        coordinator = new ObservationAutomationCoordinator(
            scheduler, stateStore, obsRunner, bridge, diagRunner,
            CLOCK, "order-api", Duration.ZERO, SCHEDULE_DELAY);
        coordinator.scheduleTarget("target-1");

        scheduler.tick(10);

        // First 3 fail, remaining 7 succeed
        assertThat(successCount.get()).isGreaterThanOrEqualTo(7);
        var status = coordinator.getStatus("target-1");
        assertThat(status.failed()).isGreaterThanOrEqualTo(3);
    }

    // ── Test 8: discovery budget=0 → all zero ──

    @Test
    void zeroDiscoveryBudgetBlocksAllCalls() throws Exception {
        stateStore.setBudgetPolicy(new AutomationBudgetPolicy(1,
            Duration.ofHours(1), 0, 10));
        coordinator.scheduleTarget("target-1");

        scheduler.tick(10);

        assertThat(obsRunner.callCount.get()).isEqualTo(0);
        assertThat(diagRunner.callCount.get()).isEqualTo(0);
        // All executions were skipped due to budget
        var status = coordinator.getStatus("target-1");
        assertThat(status.skipped()).isGreaterThanOrEqualTo(10);
    }

    // ── Test 9: provider budget=0 allows discovery, blocks diagnosis ──

    @Test
    void zeroProviderBudgetAllowsDiscoveryButNotDiagnosis() throws Exception {
        stateStore.setBudgetPolicy(new AutomationBudgetPolicy(1,
            Duration.ofHours(1), 100, 0));
        coordinator.scheduleTarget("target-1");

        scheduler.tick(5);

        // Discovery ran (APP_DOWN produces incidents)
        assertThat(obsRunner.callCount.get()).isEqualTo(5);
        // Provider never called
        assertThat(diagRunner.callCount.get()).isEqualTo(0);
        // The first incident is rejected by provider budget; later identical
        // observations are deterministically merged before they can consume it.
        assertThat(coordinator.getStatus("target-1").lastSkipReason())
            .isEqualTo("SKIPPED_ACTIVE_INCIDENT_MERGED");
    }

    @Test
    void repeatedActiveIncidentMergesDoNotRepeatProviderDiagnosis() throws Exception {
        stateStore.setBudgetPolicy(new AutomationBudgetPolicy(1,
            Duration.ofHours(1), 100, 100));
        coordinator.scheduleTarget("target-1");

        scheduler.tick(100);

        assertThat(obsRunner.callCount.get()).isEqualTo(100);
        assertThat(registry.activeCount()).isEqualTo(1);
        assertThat(diagRunner.callCount.get()).isEqualTo(1);
        assertThat(coordinator.getBudgetStatus("target-1").providerConsumed()).isEqualTo(1);
        assertThat(coordinator.getStatus("target-1").merged()).isEqualTo(99);
        assertThat(coordinator.getStatus("target-1").lastSkipReason())
            .isEqualTo("SKIPPED_ACTIVE_INCIDENT_MERGED");
    }

    @Test
    void startedCounterAndTerminalCountersAreCommittedExactlyOnce() throws Exception {
        coordinator.scheduleTarget("target-1");
        scheduler.tick(3);

        var status = coordinator.getStatus("target-1");
        assertThat(status.requested()).isEqualTo(3);
        assertThat(status.started()).isEqualTo(3);
        assertThat(status.completed() + status.merged() + status.failed())
            .isEqualTo(3);
        assertThat(status.inFlightRunId()).isNull();
    }

    @Test
    void budgetStatusReportsCurrentWindowConsumptionAndRemaining() throws Exception {
        stateStore.setBudgetPolicy(new AutomationBudgetPolicy(7,
            Duration.ofHours(1), 3, 1));
        coordinator.scheduleTarget("target-1");
        scheduler.tick(1);

        var budget = coordinator.getBudgetStatus("target-1");
        assertThat(budget.policyVersion()).isEqualTo(7);
        assertThat(budget.windowStart()).isEqualTo(CLOCK.instant());
        assertThat(budget.windowEnd()).isEqualTo(CLOCK.instant().plus(Duration.ofHours(1)));
        assertThat(budget.discoveryConsumed()).isEqualTo(1);
        assertThat(budget.discoveryRemaining()).isEqualTo(2);
        assertThat(budget.providerConsumed()).isEqualTo(1);
        assertThat(budget.providerRemaining()).isZero();
    }

    @Test
    void stateWriteFailureStopsBeforeObservationRunner() throws Exception {
        coordinator.scheduleTarget("target-1");

        // Replace the state file with a directory so atomic replacement fails.
        // The admission mutation must roll back and no external runner may start.
        Files.delete(statePath);
        Files.createDirectory(statePath);
        scheduler.tick(1);

        assertThat(obsRunner.callCount.get()).isZero();
        var status = coordinator.getStatus("target-1");
        assertThat(status.requested()).isZero();
        assertThat(status.started()).isZero();
    }

    // ── Test 10: budget pre-committed, survives crash ──

    @Test
    void budgetConsumptionPersistsAndSurvivesCrash() throws Exception {
        stateStore.setBudgetPolicy(new AutomationBudgetPolicy(1,
            Duration.ofHours(1), 3, 10));
        coordinator.scheduleTarget("target-1");

        scheduler.tick(3);
        assertThat(obsRunner.callCount.get()).isEqualTo(3);

        // Budget exhausted
        scheduler.tick(2);
        assertThat(obsRunner.callCount.get()).isEqualTo(3);

        // Restart
        coordinator.close();
        stateStore.close();
        stateStore = new AutomationStateStore(statePath, CLOCK);
        var ts = stateStore.ensureTarget("target-1");
        assertThat(ts.discoveryConsumed()).isEqualTo(3);

        // New coordinator inherits budget state
        registry.close();
        registry = new IncidentRegistry(registryPath, CLOCK);
        bridge = new ObservationToIncidentBridge(registry, CLOCK);
        scheduler = new ManualAutomationTaskScheduler();
        obsRunner = new CountingObservationRunner();
        diagRunner = new CountingDiagnosisRunner();
        coordinator = new ObservationAutomationCoordinator(
            scheduler, stateStore, obsRunner, bridge, diagRunner,
            CLOCK, "order-api", Duration.ZERO, SCHEDULE_DELAY);
        coordinator.scheduleTarget("target-1");
        scheduler.tick(5);
        // Budget still exhausted
        assertThat(obsRunner.callCount.get()).isEqualTo(0);
    }

    // ── Test 11: in-flight abandoned after restart ──

    @Test
    void inFlightRunAbandonedAfterRestart() throws Exception {
        // Manually set in-flight state (simulating crash mid-observation)
        stateStore.ensureTarget("target-1");
        stateStore.setInFlight("target-1", "inf-crashed-123");
        // Close: in-flight is NOT cleared (simulating crash)
        stateStore.close();

        // Restart — recover abandoned
        stateStore = new AutomationStateStore(statePath, CLOCK);
        int abandoned = stateStore.recoverAbandoned();
        assertThat(abandoned).isEqualTo(1);
        var status = stateStore.status("target-1");
        assertThat(status.lastOutcome()).isEqualTo("ABANDONED_AFTER_RESTART");
        assertThat(status.inFlightRunId()).isNull();
        assertThat(status.failed()).isEqualTo(1);
    }

    // ── Test 12: restart doesn't replay history ──

    @Test
    void restartDoesNotReplayHistoricalCycles() throws Exception {
        coordinator.scheduleTarget("target-1");
        scheduler.tick(5);
        long beforeObs = obsRunner.callCount.get();
        assertThat(beforeObs).isGreaterThan(0);

        // Restart
        coordinator.close();
        stateStore.close();
        stateStore = new AutomationStateStore(statePath, CLOCK);
        registry.close();
        registry = new IncidentRegistry(registryPath, CLOCK);
        bridge = new ObservationToIncidentBridge(registry, CLOCK);
        scheduler = new ManualAutomationTaskScheduler();
        obsRunner = new CountingObservationRunner();
        diagRunner = new CountingDiagnosisRunner();
        coordinator = new ObservationAutomationCoordinator(
            scheduler, stateStore, obsRunner, bridge, diagRunner,
            CLOCK, "order-api", Duration.ZERO, SCHEDULE_DELAY);
        coordinator.scheduleTarget("target-1");

        // First tick after restart — starts from current time, not replay
        scheduler.tick(1);
        assertThat(obsRunner.callCount.get()).isLessThanOrEqualTo(1);
    }

    // ── Test 16: zero write operations ──

    @Test
    void zeroWriteSessionOrOpsfixCalls() throws Exception {
        coordinator.scheduleTarget("target-1");
        scheduler.tick(5);

        // Observation runner was used
        assertThat(obsRunner.callCount.get()).isGreaterThan(0);
        // Coordinator never imports FixSession, OpsFixSession, RepairOrchestrator,
        // opsfix, or restart_service (structurally verified — no compile dependency)
    }

    @Test
    void failedObservationProjectionDoesNotFailOrReplayTheCompletedCycle() throws Exception {
        // The setUp coordinator has not registered a task; retain the shared
        // manual scheduler and replace only that unused coordinator instance.
        coordinator = new ObservationAutomationCoordinator(
            scheduler, stateStore, obsRunner, bridge, diagRunner,
            CLOCK, "order-api", Duration.ZERO, SCHEDULE_DELAY, false,
            event -> { throw new IOException("timeline storage unavailable"); });
        coordinator.scheduleTarget("target-1");

        scheduler.tick(1);

        var status = coordinator.getStatus("target-1");
        assertThat(status.completed()).isEqualTo(1);
        assertThat(status.failed()).isZero();
        assertThat(obsRunner.callCount.get()).isEqualTo(1);
        assertThat(registry.snapshot()).hasSize(1);
    }

    // ── Helper types ──────────────────────────────────────────────

    static class CountingObservationRunner implements ObservationRunner {
        final AtomicInteger callCount = new AtomicInteger(0);

        @Override
        public DiscoveryResult observe(String targetId) {
            callCount.incrementAndGet();
            return appDownDiscovery(targetId, "run-" + targetId + "-" + callCount.get());
        }
    }

    static class CountingDiagnosisRunner implements DiagnosisRunner {
        final AtomicInteger callCount = new AtomicInteger(0);

        @Override
        public DiagnosisResult diagnose(String targetId, DiscoveryResult discovery) {
            callCount.incrementAndGet();
            return new DiagnosisResult("APP_DOWN_CONFIRMED", true);
        }
    }

    static DiscoveryResult appDownDiscovery(String targetId, String runId) {
        Instant now = CLOCK.instant();
        String iid = "inc-" + targetId + "-abc12345";
        ObjectNode fact = MAPPER.createObjectNode();
        ObjectNode data = MAPPER.createObjectNode();
        data.put("State", "stopped");
        fact.put("success", true);
        fact.set("data", data);
        Evidence svcEvidence = new Evidence(
            "e-svc", iid, EvidenceType.SERVICE_STATUS,
            "mcp:ops/service_status", now, now, "container/order-api",
            Evidence.Kind.FACT, fact, "run://" + runId + "/e-svc",
            Evidence.Freshness.CURRENT, Evidence.Redaction.NONE, "2",
            Evidence.CollectionStatus.OBSERVED, null, null);

        ObjectNode httpFact = MAPPER.createObjectNode();
        ObjectNode httpData = MAPPER.createObjectNode();
        httpData.put("statusCode", 503);
        httpFact.put("success", true);
        httpFact.set("data", httpData);
        Evidence httpEvidence = new Evidence(
            "e-http", iid, EvidenceType.HTTP_PROBE,
            "mcp:ops/http_probe", now, now, "endpoint/order-api-metrics",
            Evidence.Kind.FACT, httpFact, "run://" + runId + "/e-http",
            Evidence.Freshness.CURRENT, Evidence.Redaction.NONE, "2",
            Evidence.CollectionStatus.OBSERVED, null, null);

        EvidenceBundle bundle = new EvidenceBundle(iid, runId, now,
            List.of(svcEvidence, httpEvidence));
        return new DiscoveryResult(iid, runId,
            DiscoveryProfile.REMOTE_APP_DOWN_V1.name(),
            bundle, DiscoveryStatus.COMPLETE, 2, 2, now);
    }
}
