package com.clawkit.ops.delivery.managed;

import com.clawkit.ops.loop.managed.*;
import com.clawkit.ops.mcp.LogSanitizer;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;

/** Alertmanager webhook v4 normalization. Labels/annotations are untrusted; URLs are never followed. */
public final class AlertmanagerInput {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(8192).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ManagedTriggerStore store; private final Clock clock;
    public AlertmanagerInput(ManagedTriggerStore store,Clock clock) { this.store=store; this.clock=clock; }
    public ManagedTriggerStore.IngestResult accept(byte[] bytes) throws Exception {
        String payloadHash=hash(bytes);
        try {
            if(bytes.length>65536) throw new IllegalArgumentException("alert payload exceeds 65536 bytes");
            var config=store.config(); if(config==null || !config.enabled()) throw new IllegalArgumentException("alert source disabled");
            var body=JSON.readTree(bytes);
            if(body==null || !body.isObject() || !"4".equals(body.path("version").asText()) || !body.path("alerts").isArray()
                    || body.path("alerts").size()>32 || !Set.of("firing","resolved").contains(body.path("status").asText()))
                throw new IllegalArgumentException("bounded Alertmanager webhook v4 required");
            if(!body.path("truncatedAlerts").isIntegralNumber() || !body.path("truncatedAlerts").canConvertToInt() || body.path("truncatedAlerts").asInt()<0)
                throw new IllegalArgumentException("nonnegative truncatedAlerts required");
            boolean truncated=body.path("truncatedAlerts").asInt()>0; String group=text(body,"groupKey",1000); Instant received=clock.instant();
            var triggers=new ArrayList<TriggerEnvelope>();
            for(var alert:body.path("alerts")) {
                if(!alert.isObject() || !alert.path("labels").isObject() || alert.path("labels").size()>32 || !alert.path("annotations").isObject()
                        || alert.path("annotations").size()>32) throw new IllegalArgumentException("bounded alert labels/annotations required");
                String status=text(alert,"status",16); var phase=switch(status) { case "firing" -> TriggerEnvelope.Phase.FIRING; case "resolved" -> TriggerEnvelope.Phase.RESOLVED;
                    default -> throw new IllegalArgumentException("unknown alert phase"); };
                if(body.path("status").asText().equals("resolved") && phase==TriggerEnvelope.Phase.FIRING) throw new IllegalArgumentException("group phase conflicts with alert phase");
                String fingerprint=text(alert,"fingerprint",64); if(!fingerprint.matches("[a-fA-F0-9]{16,64}")) throw new IllegalArgumentException("alert fingerprint required");
                Instant starts=Instant.parse(text(alert,"startsAt",100));
                Instant ends=phase==TriggerEnvelope.Phase.RESOLVED ? Instant.parse(text(alert,"endsAt",100)) : null;
                String environment=optional(alert.path("labels"),"clawkit_environment",250,"UNRESOLVED");
                String service=optional(alert.path("labels"),"clawkit_service",250,"UNRESOLVED");
                var matches=config.bindings().stream().filter(b -> b.environment().equals(environment) && b.service().equals(service)).toList();
                var binding=matches.size()==1 ? matches.getFirst() : null;
                String summary=optional(alert.path("annotations"),"summary",8192,"No summary supplied"); boolean summaryTruncated=summary.length()>1000;
                summary=LogSanitizer.sanitizeAll(summary).text(); if(summary.length()>1000) summary=summary.substring(0,1000);
                var quality=truncated || summaryTruncated ? TriggerEnvelope.Quality.TRUNCATED : binding==null ? TriggerEnvelope.Quality.PENDING_TARGET : TriggerEnvelope.Quality.COMPLETE;
                if(starts.isAfter(received.plusSeconds(5)) || starts.isBefore(received.minus(Duration.ofDays(1)))
                        || ends!=null && (ends.isBefore(starts) || ends.isAfter(received.plusSeconds(5)))) quality=TriggerEnvelope.Quality.INVALID_TIME;
                String episode=hash((config.sourceId()+"|"+fingerprint.toLowerCase(Locale.ROOT)+"|"+starts).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String event=hash((episode+"|"+phase+"|"+(ends==null ? "" : ends)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                triggers.add(new TriggerEnvelope(event,episode,config.sourceId(),config.version(),fingerprint,group,phase,starts,ends,received,
                    binding==null ? null : binding.applicationId(),binding==null ? 0 : binding.applicationVersion(),binding==null ? null : binding.applicationHash(),
                    binding==null ? null : binding.targetHash(),environment,service,optional(alert.path("labels"),"alertname",250,"Unnamed alert"),
                    optional(alert.path("labels"),"severity",100,"unknown"),summary,quality,payloadHash));
            }
            return store.ingest(triggers);
        } catch(Exception e) {
            store.reject(e instanceof com.fasterxml.jackson.core.JsonProcessingException ? "INVALID_JSON" : "INVALID_SOURCE_CONTRACT",payloadHash);
            if(e instanceof com.fasterxml.jackson.core.JsonProcessingException || e instanceof java.time.format.DateTimeParseException || e instanceof IllegalArgumentException)
                throw new IllegalArgumentException("invalid Alertmanager source contract; bounded rejection retained");
            throw e;
        }
    }
    private static String text(JsonNode node,String name,int cap) {
        if(!node.path(name).isTextual()) throw new IllegalArgumentException("required alert text missing"); String value=node.path(name).asText();
        if(value.isBlank() || value.length()>cap) throw new IllegalArgumentException("bounded alert text required"); return value;
    }
    private static String optional(JsonNode node,String name,int cap,String fallback) { return node.has(name) ? text(node,name,cap) : fallback; }
    public static String hash(byte[] bytes) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(Exception e) { throw new IllegalStateException(e); } }
}
