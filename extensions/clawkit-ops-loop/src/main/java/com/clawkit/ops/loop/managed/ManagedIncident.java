package com.clawkit.ops.loop.managed;

import java.time.Instant;
import java.util.List;

/** Persistent current incident. Full model/repair artifacts are separate from the small controller snapshot. */
public record ManagedIncident(String id,String applicationId,String applicationHash,String target,
        State state,Instant createdAt,Instant lastObservedAt,long observations,int decisions,
        Instant nextDecisionAt,Instant deadline,String symptomHash,List<DecisionEvidence> evidence,
        OpsDecision decision,List<DecisionEvidence> decisionEvidence,String decisionArtifact,
        RepairSummary repair,String detail) {
    public enum State { OPEN, INVESTIGATING, WAITING, AWAITING_APPROVAL, EXECUTING, RECOVERED, HANDOFF, CANCELLED }
    public record RepairSummary(ManagedRepairExecutor.Status status,String attemptId,
                               com.clawkit.reliability.attempt.AttemptState attemptState,String artifact) {}
    public ManagedIncident {
        ManagedApplication.identifier(id); ManagedApplication.identifier(applicationId);
        java.util.Objects.requireNonNull(applicationHash); java.util.Objects.requireNonNull(target);
        java.util.Objects.requireNonNull(state); java.util.Objects.requireNonNull(createdAt);
        java.util.Objects.requireNonNull(lastObservedAt); java.util.Objects.requireNonNull(deadline);
        evidence=List.copyOf(evidence); decisionEvidence=List.copyOf(decisionEvidence);
        if (observations<1 || decisions<0 || decisions>12 || detail==null || detail.length()>2000)
            throw new IllegalArgumentException("invalid bounded incident state");
    }
    // A rejected incident suppresses repeats until independent recovery; it must not instantly become a new incident.
    public boolean terminal() { return state==State.RECOVERED; }
}
