package com.clawkit.ops.loop.autonomy;

import java.io.IOException;
import java.util.Objects;

/**
 * Records the complete A3 counterfactual in a durable order: immutable policy
 * first, then a zero-side-effect decision. It intentionally owns no executor.
 */
public final class ShadowWorkflow {
    private final ShadowPolicyStore policies;
    private final ShadowDecisionStore decisions;
    private final ShadowPolicyGate gate;

    public ShadowWorkflow(ShadowPolicyStore policies, ShadowDecisionStore decisions, ShadowPolicyGate gate) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    public synchronized ShadowDecisionStore.RecordedDecision evaluateAndRecord(AutoRemediationPolicy policy,
                                                                                 ShadowEvaluationRequest request)
        throws IOException {
        AutoRemediationPolicy durablePolicy = policies.put(policy);
        ShadowDecision candidate = gate.evaluate(durablePolicy, request);
        return decisions.withExclusive(() -> {
            if (decisions.find(candidate.decisionId()).isPresent()) {
                return decisions.recordWithinExclusive(candidate);
            }
            ShadowDecision gatedCandidate = candidate;
            if (gatedCandidate.outcome() == ShadowOutcome.ELIGIBLE_SHADOW
                && decisions.countEligibleByPolicyHash(durablePolicy.policyHash())
                    >= durablePolicy.maxShadowDecisions()) {
                gatedCandidate = gatedCandidate.withOutcome(ShadowOutcome.ASK_REQUIRED,
                    "SHADOW_DECISION_LIMIT_REACHED");
            }
            return decisions.recordWithinExclusive(gatedCandidate);
        });
    }
}
