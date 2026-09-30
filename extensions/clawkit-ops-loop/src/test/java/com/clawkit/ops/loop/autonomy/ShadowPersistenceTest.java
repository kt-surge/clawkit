package com.clawkit.ops.loop.autonomy;

import com.clawkit.ops.loop.Diagnosis;
import com.clawkit.ops.loop.repair.RepairSuggestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class ShadowPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private static final String SNAPSHOT = "b".repeat(64);

    @TempDir Path tempDir;

    @Test
    void immutablePolicyRoundTripsWithItsContentHash() throws Exception {
        var store = new ShadowPolicyStore(tempDir.resolve("policies"));
        var policy = AutoRemediationPolicy.fixtureAppDownShadow("fixture-shadow-v1", NOW.plusSeconds(300));

        assertThat(store.put(policy)).isEqualTo(policy);
        assertThat(store.load(policy.policyHash())).isEqualTo(policy);
        try (var paths = Files.list(tempDir.resolve("policies"))) {
            assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".json")).count())
                .isEqualTo(1);
        }
    }

    @Test
    void directPolicyStoreCallsSerializeConcurrentFirstCreation() throws Exception {
        var directory = tempDir.resolve("policies");
        var policy = AutoRemediationPolicy.fixtureAppDownShadow("fixture-shadow-v1", NOW.plusSeconds(300));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> putPolicyAfterBarrier(directory, policy, ready, start));
            var second = pool.submit(() -> putPolicyAfterBarrier(directory, policy, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(policy);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(policy);
        }
        try (var paths = Files.list(directory)) {
            assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".json")).count())
                .isEqualTo(1);
        }
    }

    @Test
    void sameShadowIdentityIsPersistedOnlyOnce() throws Exception {
        var store = new ShadowDecisionStore(tempDir.resolve("decisions"));
        var decision = eligibleDecision();

        var first = store.record(decision);
        var replay = store.record(decision);

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.decision()).isEqualTo(first.decision());
        assertThat(store.list()).containsExactly(decision);
    }

    @Test
    void directDecisionStoreCallsAlsoCannotRacePastImmutability() throws Exception {
        var directory = tempDir.resolve("decisions");
        var decision = eligibleDecision();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> recordDecisionAfterBarrier(directory, decision, ready, start));
            var second = pool.submit(() -> recordDecisionAfterBarrier(directory, decision, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(5, TimeUnit.SECONDS).created(),
                second.get(5, TimeUnit.SECONDS).created())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(new ShadowDecisionStore(directory).list()).containsExactly(decision);
    }

    @Test
    void tamperedDecisionFailsClosedOnRead() throws Exception {
        var store = new ShadowDecisionStore(tempDir.resolve("decisions"));
        var decision = eligibleDecision();
        store.record(decision);
        Files.writeString(store.pathFor(decision.decisionId()), "{}\n# SHA-256: " + "0".repeat(64) + "\n");

        assertThatThrownBy(() -> store.load(decision.decisionId()))
            .hasMessageContaining("checksum mismatch");
    }

    @Test
    void deterministicIdDoesNotChangeAcrossEquivalentEvaluation() {
        var gate = new ShadowPolicyGate(Clock.fixed(NOW, ZoneOffset.UTC));
        var policy = AutoRemediationPolicy.fixtureAppDownShadow("fixture-shadow-v1", NOW.plusSeconds(300));

        var first = gate.evaluate(policy, request());
        var second = gate.evaluate(policy, request());

        assertThat(first.decisionId()).isEqualTo(second.decisionId());
        assertThat(first.sideEffectCalls()).isZero();
    }

    @Test
    void workflowPersistsPolicyBeforeAnIdempotentZeroSideEffectDecision() throws Exception {
        var workflow = new ShadowWorkflow(
            new ShadowPolicyStore(tempDir.resolve("policies")),
            new ShadowDecisionStore(tempDir.resolve("decisions")),
            new ShadowPolicyGate(Clock.fixed(NOW, ZoneOffset.UTC)));
        var policy = AutoRemediationPolicy.fixtureAppDownShadow("fixture-shadow-v1", NOW.plusSeconds(300));

        var first = workflow.evaluateAndRecord(policy, request());
        var replay = workflow.evaluateAndRecord(policy, request());

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(first.decision().sideEffectCalls()).isZero();
        assertThat(first.decision().outcome()).isEqualTo(ShadowOutcome.ELIGIBLE_SHADOW);
    }

    @Test
    void workflowStopsNewEligibleShadowWhenItsPolicyLimitIsReached() throws Exception {
        var workflow = new ShadowWorkflow(
            new ShadowPolicyStore(tempDir.resolve("policies")),
            new ShadowDecisionStore(tempDir.resolve("decisions")),
            new ShadowPolicyGate(Clock.fixed(NOW, ZoneOffset.UTC)));
        var policy = new AutoRemediationPolicy(1, "fixture-shadow-limit", AutonomyLevel.A3_SHADOW,
            "FIXTURE", "fixture-app-down", "fixture-observe-only", "restart_service", "order-api",
            1, 1, NOW.plusSeconds(300), true);

        var first = workflow.evaluateAndRecord(policy, request());
        var second = workflow.evaluateAndRecord(policy, requestFor("inc-2", "c".repeat(64)));

        assertThat(first.decision().outcome()).isEqualTo(ShadowOutcome.ELIGIBLE_SHADOW);
        assertThat(second.decision().outcome()).isEqualTo(ShadowOutcome.ASK_REQUIRED);
        assertThat(second.decision().reasonCodes()).containsExactly("SHADOW_DECISION_LIMIT_REACHED");
        assertThat(second.decision().sideEffectCalls()).isZero();
    }

    @Test
    void separateWorkflowsCannotRacePastTheSharedPolicyLimit() throws Exception {
        var policyDirectory = tempDir.resolve("policies");
        var decisionDirectory = tempDir.resolve("decisions");
        var policy = new AutoRemediationPolicy(1, "fixture-shadow-race", AutonomyLevel.A3_SHADOW,
            "FIXTURE", "fixture-app-down", "fixture-observe-only", "restart_service", "order-api",
            1, 1, NOW.plusSeconds(300), true);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> evaluateAfterBarrier(policyDirectory, decisionDirectory, policy,
                requestFor("inc-race-1", "d".repeat(64)), ready, start));
            var second = pool.submit(() -> evaluateAfterBarrier(policyDirectory, decisionDirectory, policy,
                requestFor("inc-race-2", "e".repeat(64)), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var outcomes = List.of(first.get(5, TimeUnit.SECONDS).decision().outcome(),
                second.get(5, TimeUnit.SECONDS).decision().outcome());

            assertThat(outcomes).containsExactlyInAnyOrder(ShadowOutcome.ELIGIBLE_SHADOW, ShadowOutcome.ASK_REQUIRED);
            assertThat(new ShadowDecisionStore(decisionDirectory).countEligibleByPolicyHash(policy.policyHash()))
                .isEqualTo(1);
        }
    }

    private static ShadowDecisionStore.RecordedDecision evaluateAfterBarrier(Path policyDirectory,
        Path decisionDirectory, AutoRemediationPolicy policy, ShadowEvaluationRequest request,
        CountDownLatch ready, CountDownLatch start) throws Exception {
        var workflow = new ShadowWorkflow(new ShadowPolicyStore(policyDirectory),
            new ShadowDecisionStore(decisionDirectory), new ShadowPolicyGate(Clock.fixed(NOW, ZoneOffset.UTC)));
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test start barrier timed out");
        return workflow.evaluateAndRecord(policy, request);
    }

    private static ShadowDecisionStore.RecordedDecision recordDecisionAfterBarrier(Path directory,
        ShadowDecision decision, CountDownLatch ready, CountDownLatch start) throws Exception {
        var store = new ShadowDecisionStore(directory);
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test start barrier timed out");
        return store.record(decision);
    }

    private static AutoRemediationPolicy putPolicyAfterBarrier(Path directory, AutoRemediationPolicy policy,
        CountDownLatch ready, CountDownLatch start) throws Exception {
        var store = new ShadowPolicyStore(directory);
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test start barrier timed out");
        return store.put(policy);
    }

    private static ShadowDecision eligibleDecision() {
        var policy = AutoRemediationPolicy.fixtureAppDownShadow("fixture-shadow-v1", NOW.plusSeconds(300));
        return new ShadowPolicyGate(Clock.fixed(NOW, ZoneOffset.UTC)).evaluate(policy, request());
    }

    private static ShadowEvaluationRequest request() {
        return requestFor("inc-1", SNAPSHOT);
    }

    private static ShadowEvaluationRequest requestFor(String incidentId, String snapshot) {
        var diagnosis = new Diagnosis("APP_DOWN", 0.9, List.of("e-1", "e-2"), List.of(), List.of(), List.of(),
            "RESTART_SERVICE", false, "1", Diagnosis.DiagnosisStatus.CONFIRMED,
            Diagnosis.CurrentCondition.ACTIVE, NOW, Diagnosis.ResolutionAttribution.NONE);
        var suggestion = new RepairSuggestion(incidentId, "restart_service", "order-api", "fixture", 0.9,
            List.of("e-1", "e-2"));
        return new ShadowEvaluationRequest(incidentId, "fixture-app-down", snapshot, NOW, diagnosis,
            suggestion, ModelOpinion.SUPPORTS_ACTION);
    }
}
