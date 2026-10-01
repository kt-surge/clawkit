package com.clawkit.ops.loop.managed;

import java.time.Instant;
import java.util.Objects;

/** A reversible dependency/time relationship. It never combines incident authority or claims a shared root cause. */
public record IncidentRelation(String id,String leftEventId,String rightEventId,String leftIncidentId,String rightIncidentId,
        String topologyHash,Instant windowStart,Instant windowEnd,String reason,State state,String revocationReason) {
    public enum State { ACTIVE, REVOKED }
    public IncidentRelation { OpsKnowledge.hash(id); OpsKnowledge.hash(leftEventId); OpsKnowledge.hash(rightEventId); OpsKnowledge.hash(topologyHash);
        Objects.requireNonNull(windowStart); Objects.requireNonNull(windowEnd); Objects.requireNonNull(state); OpsKnowledge.text(reason,500);
        if(windowEnd.isBefore(windowStart) || leftEventId.equals(rightEventId)) throw new IllegalArgumentException("valid relation interval required");
        if(leftIncidentId!=null) ManagedApplication.identifier(leftIncidentId); if(rightIncidentId!=null) ManagedApplication.identifier(rightIncidentId);
        if(state==State.REVOKED) OpsKnowledge.text(revocationReason,250);
    }
}
