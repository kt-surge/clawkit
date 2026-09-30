package com.clawkit.ops.loop.managed;

import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.action.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

final class ManagedContracts {
    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    static String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("contract hashing failed",e); }
    }

    static String target(ManagedApplication app) {
        return "compose://" + app.targetId() + "/" + app.composeProject() + "/" + app.service();
    }

    /** Stable facts only: fresh collection gets new IDs/times but must preserve the approved conditions. */
    static String snapshot(List<DecisionEvidence> evidence) {
        return hash(evidence.stream().map(e -> e.observation())
            .sorted(java.util.Comparator.comparing(o -> o.probe().name()))
            .map(o -> List.of(o.targetId(),o.composeProject(),o.service(),o.probe().name(),o.status().name(),o.detail())).distinct().toList());
    }

    static ActionDescriptor descriptor(String incidentId, ManagedApplication app, OpsDecision.Playbook playbook) {
        return new ActionDescriptor(playbook.action(),target(app),
            "incident=" + incidentId + " config=" + hash(app) + " playbook=" + playbook.name(),
            ToolRiskLevel.HIGH,Reversibility.IRREVERSIBLE,ActionReliability.none(),VerificationMode.WORKFLOW,
            List.of("registered stateless application; fresh domain precheck under target mutex"),
            List.of("independent sustained service, health and business recovery"),
            "Stop further attempts and hand off; no automatic stop/rollback",
            "Only registered Compose service; no dependency/config/image mutation");
    }
}
