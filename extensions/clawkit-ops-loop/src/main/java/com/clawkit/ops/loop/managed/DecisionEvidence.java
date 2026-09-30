package com.clawkit.ops.loop.managed;

import java.time.Instant;

/** Controller-assigned reference and identity, independent of model-generated arguments. */
public record DecisionEvidence(String id, String applicationId, long applicationVersion,
        ManagedObserver.Observation observation, Instant validUntil, EvidenceEnvelope envelope) {
    public DecisionEvidence(String id,String applicationId,long applicationVersion,ManagedObserver.Observation observation,Instant validUntil) {
        this(id,applicationId,applicationVersion,observation,validUntil,null);
    }
    public DecisionEvidence {
        if (envelope!=null && (!id.equals(envelope.evidenceId()) || !applicationId.equals(envelope.applicationId())
                || applicationVersion!=envelope.applicationVersion() || !observation.equals(envelope.observation())))
            throw new IllegalArgumentException("controller evidence and envelope differ");
    }
    public boolean currentAt(Instant now) {
        return !now.isBefore(observation.observedAt()) && now.isBefore(validUntil);
    }
}
