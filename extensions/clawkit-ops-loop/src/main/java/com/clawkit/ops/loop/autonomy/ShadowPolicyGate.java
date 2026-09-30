package com.clawkit.ops.loop.autonomy;

import com.clawkit.ops.loop.repair.GateDecision;
import com.clawkit.ops.loop.repair.RepairPolicyGate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A3-only evaluator. It deliberately has no FixSession, opsfix, or dispatch dependency.
 */
public final class ShadowPolicyGate {
    private static final Duration MAX_EVIDENCE_AGE = Duration.ofMinutes(5);

    private final Clock clock;

    public ShadowPolicyGate(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ShadowDecision evaluate(AutoRemediationPolicy policy, ShadowEvaluationRequest request) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(request, "request");
        Instant now = clock.instant();
        String decisionId = ShadowDecision.deterministicId(request.incidentId(), request.targetId(),
            request.evidenceSnapshotHash(), policy.policyHash(), request.suggestion().actionCode(),
            request.suggestion().serviceId());
        if (policy.isExpiredAt(now)) {
            return decision(decisionId, now, policy, request, ShadowOutcome.EXPIRED, "POLICY_EXPIRED");
        }
        if (!policy.enabled()) {
            return decision(decisionId, now, policy, request, ShadowOutcome.ASK_REQUIRED, "POLICY_DISABLED");
        }
        if (!policy.targetId().equals(request.targetId())) {
            return decision(decisionId, now, policy, request, ShadowOutcome.REJECTED, "TARGET_NOT_ALLOWED");
        }
        if (request.diagnosis().claimedResolved()
            || request.diagnosis().currentCondition()
                != com.clawkit.ops.loop.Diagnosis.CurrentCondition.ACTIVE) {
            return decision(decisionId, now, policy, request, ShadowOutcome.ASK_REQUIRED, "CONDITION_NOT_ACTIVE");
        }
        if (request.diagnosis().supportingEvidence().isEmpty()
            || !request.diagnosis().missingEvidence().isEmpty()) {
            return decision(decisionId, now, policy, request, ShadowOutcome.ASK_REQUIRED, "EVIDENCE_INSUFFICIENT");
        }
        if (request.evidenceObservedAt().plus(MAX_EVIDENCE_AGE).isBefore(now)) {
            return decision(decisionId, now, policy, request, ShadowOutcome.ASK_REQUIRED, "EVIDENCE_STALE");
        }
        if (request.modelOpinion() == ModelOpinion.OPPOSES_ACTION) {
            return decision(decisionId, now, policy, request, ShadowOutcome.ASK_REQUIRED, "MODEL_OPPOSES_ACTION");
        }
        if (!policy.actionCode().equals(request.suggestion().actionCode())
            || !policy.serviceId().equals(request.suggestion().serviceId())) {
            return decision(decisionId, now, policy, request, ShadowOutcome.REJECTED, "ACTION_NOT_ALLOWED");
        }
        GateDecision gateDecision = RepairPolicyGate.evaluate(request.diagnosis(), request.suggestion());
        if (gateDecision.denied()) {
            return decision(decisionId, now, policy, request, ShadowOutcome.REJECTED, "REPAIR_POLICY_DENIED");
        }
        return decision(decisionId, now, policy, request, ShadowOutcome.ELIGIBLE_SHADOW, "FIXTURE_POLICY_ELIGIBLE");
    }

    private static ShadowDecision decision(String decisionId, Instant now, AutoRemediationPolicy policy,
                                           ShadowEvaluationRequest request, ShadowOutcome outcome,
                                           String reasonCode) {
        return new ShadowDecision(decisionId, now, request.incidentId(), request.targetId(),
            request.evidenceSnapshotHash(), policy.policyHash(), request.suggestion().actionCode(),
            request.suggestion().serviceId(), outcome, List.of(reasonCode), request.modelOpinion(), 0);
    }
}
