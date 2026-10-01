package com.clawkit.ops.loop.managed;

import java.time.Instant;
import java.util.Objects;

/** Authenticated source claim, never current health evidence or action permission. Event IDs are derived, not supplied native IDs. */
public record TriggerEnvelope(String eventId,String episodeId,String sourceId,long sourceVersion,String sourceFingerprint,
        String groupKey,Phase phase,Instant startsAt,Instant endsAt,Instant receivedAt,String applicationId,long applicationVersion,
        String applicationHash,String targetHash,String environment,String service,String alertName,String severity,String summary,
        Quality quality,String contentHash) {
    public enum Phase { FIRING, RESOLVED }
    public enum Quality { COMPLETE, TRUNCATED, PENDING_TARGET, INVALID_TIME }
    public TriggerEnvelope {
        OpsKnowledge.hash(eventId); OpsKnowledge.hash(episodeId); ManagedApplication.identifier(sourceId);
        if(sourceVersion<1 || sourceFingerprint==null || !sourceFingerprint.matches("[a-fA-F0-9]{16,64}")) throw new IllegalArgumentException("source identity required");
        Objects.requireNonNull(phase); Objects.requireNonNull(startsAt); Objects.requireNonNull(receivedAt); Objects.requireNonNull(quality);
        OpsKnowledge.text(groupKey,1000); OpsKnowledge.text(environment,250); OpsKnowledge.text(service,250);
        OpsKnowledge.text(alertName,250); OpsKnowledge.text(severity,100); OpsKnowledge.text(summary,1000); OpsKnowledge.hash(contentHash);
        if(applicationId!=null) { ManagedApplication.identifier(applicationId); OpsKnowledge.hash(applicationHash); OpsKnowledge.hash(targetHash);
            if(applicationVersion<1) throw new IllegalArgumentException("registered application version required"); }
        else if(applicationVersion!=0 || applicationHash!=null || targetHash!=null) throw new IllegalArgumentException("unresolved target cannot carry registered identity");
    }
}
