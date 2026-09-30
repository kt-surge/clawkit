package com.clawkit.ops.loop.autonomy;

import com.clawkit.ops.loop.Diagnosis;
import com.clawkit.ops.loop.repair.RepairSuggestion;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ShadowPolicyGateTest {
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private static final String SNAPSHOT = "a".repeat(64);

    @Test
    void eligibleShadowAlwaysReportsZeroSideEffects() {
        var decision = gate().evaluate(policy(false, NOW.plusSeconds(60)), request("fixture-app-down", NOW,
            appDown(), ModelOpinion.SUPPORTS_ACTION));

        assertThat(decision.outcome()).isEqualTo(ShadowOutcome.ELIGIBLE_SHADOW);
        assertThat(decision.sideEffectCalls()).isZero();
        assertThat(decision.reasonCodes()).containsExactly("FIXTURE_POLICY_ELIGIBLE");
    }

    @Test
    void staleEvidenceAndModelOppositionStayAsk() {
        var stale = gate().evaluate(policy(false, NOW.plusSeconds(60)), request("fixture-app-down",
            NOW.minusSeconds(301), appDown(), ModelOpinion.SUPPORTS_ACTION));
        var opposed = gate().evaluate(policy(false, NOW.plusSeconds(60)), request("fixture-app-down", NOW,
            appDown(), ModelOpinion.OPPOSES_ACTION));

        assertThat(stale.outcome()).isEqualTo(ShadowOutcome.ASK_REQUIRED);
        assertThat(stale.reasonCodes()).containsExactly("EVIDENCE_STALE");
        assertThat(opposed.outcome()).isEqualTo(ShadowOutcome.ASK_REQUIRED);
        assertThat(opposed.reasonCodes()).containsExactly("MODEL_OPPOSES_ACTION");
    }

    @Test
    void recoveredOrInsufficientDiagnosisStaysAskRequired() {
        var recovered = gate().evaluate(policy(false, NOW.plusSeconds(60)), request("fixture-app-down", NOW,
            diagnosis("APP_DOWN", Diagnosis.CurrentCondition.RECOVERED, List.of("e-1"), List.of()),
            ModelOpinion.SUPPORTS_ACTION));
        var insufficient = gate().evaluate(policy(false, NOW.plusSeconds(60)), request("fixture-app-down", NOW,
            diagnosis("APP_DOWN", Diagnosis.CurrentCondition.ACTIVE, List.of(), List.of("service-status")),
            ModelOpinion.SUPPORTS_ACTION));

        assertThat(recovered.outcome()).isEqualTo(ShadowOutcome.ASK_REQUIRED);
        assertThat(recovered.reasonCodes()).containsExactly("CONDITION_NOT_ACTIVE");
        assertThat(insufficient.outcome()).isEqualTo(ShadowOutcome.ASK_REQUIRED);
        assertThat(insufficient.reasonCodes()).containsExactly("EVIDENCE_INSUFFICIENT");
    }

    @Test
    void targetMismatchAndRepairPolicyFailureAreRejected() {
        var wrongTarget = gate().evaluate(policy(false, NOW.plusSeconds(60)), request("fixture-other", NOW,
            appDown(), ModelOpinion.SUPPORTS_ACTION));
        var dbLock = gate().evaluate(policy(false, NOW.plusSeconds(60)), request("fixture-app-down", NOW,
            diagnosis("DB_LOCK_WAIT"), ModelOpinion.SUPPORTS_ACTION));

        assertThat(wrongTarget.outcome()).isEqualTo(ShadowOutcome.REJECTED);
        assertThat(wrongTarget.reasonCodes()).containsExactly("TARGET_NOT_ALLOWED");
        assertThat(dbLock.outcome()).isEqualTo(ShadowOutcome.REJECTED);
        assertThat(dbLock.reasonCodes()).containsExactly("REPAIR_POLICY_DENIED");
    }

    @Test
    void expiredAndDisabledPolicyNeverBecomeEligible() {
        var expired = gate().evaluate(policy(false, NOW), request("fixture-app-down", NOW,
            appDown(), ModelOpinion.SUPPORTS_ACTION));
        var disabled = gate().evaluate(policy(true, NOW.plusSeconds(60)), request("fixture-app-down", NOW,
            appDown(), ModelOpinion.SUPPORTS_ACTION));

        assertThat(expired.outcome()).isEqualTo(ShadowOutcome.EXPIRED);
        assertThat(disabled.outcome()).isEqualTo(ShadowOutcome.ASK_REQUIRED);
    }

    @Test
    void decisionRejectsAnyNonzeroSideEffectCounter() {
        assertThatThrownBy(() -> new ShadowDecision("shadow-test", NOW, "inc-1", "fixture-app-down",
            SNAPSHOT, SNAPSHOT, "restart_service", "order-api", ShadowOutcome.ELIGIBLE_SHADOW,
            List.of("FIXTURE_POLICY_ELIGIBLE"), ModelOpinion.SUPPORTS_ACTION, 1))
            .hasMessageContaining("zero sideEffectCalls");
    }

    private static ShadowPolicyGate gate() {
        return new ShadowPolicyGate(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AutoRemediationPolicy policy(boolean disabled, Instant expiresAt) {
        return new AutoRemediationPolicy(1, "fixture-shadow-v1", AutonomyLevel.A3_SHADOW,
            "FIXTURE", "fixture-app-down", "fixture-observe-only", "restart_service", "order-api",
            1, 100, expiresAt, !disabled);
    }

    private static ShadowEvaluationRequest request(String targetId, Instant observedAt,
                                                   Diagnosis diagnosis, ModelOpinion opinion) {
        return new ShadowEvaluationRequest("inc-1", targetId, SNAPSHOT, observedAt, diagnosis,
            new RepairSuggestion("inc-1", "restart_service", "order-api", "fixture candidate", 0.9,
                List.of("fixture://run/service-status")), opinion);
    }

    private static Diagnosis appDown() {
        return diagnosis("APP_DOWN", Diagnosis.CurrentCondition.ACTIVE, List.of("e-1", "e-2"),
            List.of());
    }

    private static Diagnosis diagnosis(String rootCause) {
        return diagnosis(rootCause, Diagnosis.CurrentCondition.ACTIVE, List.of("e-1"), List.of());
    }

    private static Diagnosis diagnosis(String rootCause, Diagnosis.CurrentCondition condition,
                                       List<String> supportingEvidence, List<String> missingEvidence) {
        return new Diagnosis(rootCause, 0.9, supportingEvidence, List.of(), List.of(), missingEvidence,
            "RESTART_SERVICE", false, "1", Diagnosis.DiagnosisStatus.CONFIRMED,
            condition, NOW, Diagnosis.ResolutionAttribution.NONE);
    }
}
