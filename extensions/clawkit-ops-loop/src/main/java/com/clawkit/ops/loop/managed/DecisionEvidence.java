package com.clawkit.ops.loop.managed;

import java.time.Instant;

/** Controller-assigned reference and identity, independent of model-generated arguments. */
public record DecisionEvidence(String id, String applicationId, long applicationVersion,
        ManagedObserver.Observation observation, Instant validUntil) {
    public boolean currentAt(Instant now) {
        return !now.isBefore(observation.observedAt()) && now.isBefore(validUntil);
    }
}
