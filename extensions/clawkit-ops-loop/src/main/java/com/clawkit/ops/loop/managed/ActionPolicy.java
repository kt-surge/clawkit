package com.clawkit.ops.loop.managed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Permission and tested qualification are distinct; this record cannot itself dispatch an action. */
public record ActionPolicy(String applicationId, long applicationVersion, long version,
        Mode mode, Qualification qualification, Set<OpsDecision.Playbook> playbooks,
        Instant expiresAt, int maxAttemptsPerIncident) {
    public enum Mode { OBSERVE, ASK, LIMITED_AUTO }
    public enum Qualification { DRAFT, SHADOW, QUALIFIED, REVOKED }

    public ActionPolicy {
        applicationId = ManagedApplication.identifier(applicationId);
        if (applicationVersion < 1 || version < 1) throw new IllegalArgumentException("positive versions required");
        Objects.requireNonNull(mode);
        Objects.requireNonNull(qualification);
        playbooks = Set.copyOf(Objects.requireNonNull(playbooks));
        Objects.requireNonNull(expiresAt);
        if (maxAttemptsPerIncident != 1) throw new IllegalArgumentException("first release permits one dispatch per incident/action");
    }

    public static ActionPolicy ask(ManagedApplication app, Instant expiresAt) {
        return new ActionPolicy(app.id(), app.version(), 1, Mode.ASK, Qualification.DRAFT,
            Set.of(OpsDecision.Playbook.values()), expiresAt, 1);
    }

    public boolean permitsAuto(ManagedApplication app, OpsDecision.Playbook playbook, Instant now) {
        return mode == Mode.LIMITED_AUTO && qualification == Qualification.QUALIFIED
            && applicationId.equals(app.id()) && applicationVersion == app.version()
            && now.isBefore(expiresAt) && app.repairIntendedAt(now) && playbooks.contains(playbook);
    }

    public String policyHash() {
        String canonical = applicationId + "\n" + applicationVersion + "\n" + version + "\n" + mode
            + "\n" + qualification + "\n" + playbooks.stream().map(Enum::name).sorted().toList()
            + "\n" + expiresAt + "\n" + maxAttemptsPerIncident;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
