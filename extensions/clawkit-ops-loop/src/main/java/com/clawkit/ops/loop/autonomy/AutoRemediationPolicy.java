package com.clawkit.ops.loop.autonomy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable, versioned policy for one narrowly-scoped autonomy decision.
 *
 * <p>This first contract intentionally permits A3 only. It is not a permission
 * grant and cannot execute an action; A4 needs its own acceptance review.
 */
public record AutoRemediationPolicy(
    int schemaVersion,
    String policyId,
    AutonomyLevel autonomyLevel,
    String environment,
    String targetId,
    String capabilityProfile,
    String actionCode,
    String serviceId,
    int maxAttempts,
    int maxShadowDecisions,
    Instant expiresAt,
    boolean enabled
) {
    public static final int SCHEMA_VERSION = 1;
    public static final String FIXTURE_ENVIRONMENT = "FIXTURE";
    public static final String FIXTURE_TARGET = "fixture-app-down";
    public static final String FIXTURE_CAPABILITY_PROFILE = "fixture-observe-only";
    public static final String RESTART_SERVICE = "restart_service";
    public static final String ORDER_API = "order-api";

    public AutoRemediationPolicy {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported policy schemaVersion: " + schemaVersion);
        }
        requireId(policyId, "policyId");
        autonomyLevel = Objects.requireNonNull(autonomyLevel, "autonomyLevel");
        if (autonomyLevel != AutonomyLevel.A3_SHADOW) {
            throw new IllegalArgumentException("only A3_SHADOW policies are accepted before A4 review");
        }
        if (!FIXTURE_ENVIRONMENT.equals(environment)) {
            throw new IllegalArgumentException("Shadow policy environment must be FIXTURE");
        }
        if (!FIXTURE_TARGET.equals(targetId)) {
            throw new IllegalArgumentException("Shadow policy targetId must be fixture-app-down");
        }
        if (!FIXTURE_CAPABILITY_PROFILE.equals(capabilityProfile)) {
            throw new IllegalArgumentException("Shadow policy capabilityProfile is not allowed");
        }
        if (!RESTART_SERVICE.equals(actionCode) || !ORDER_API.equals(serviceId)) {
            throw new IllegalArgumentException("Shadow policy only permits restart_service(order-api)");
        }
        if (maxAttempts != 1) {
            throw new IllegalArgumentException("Shadow policy maxAttempts must remain 1");
        }
        if (maxShadowDecisions < 1 || maxShadowDecisions > 1000) {
            throw new IllegalArgumentException("maxShadowDecisions must be between 1 and 1000");
        }
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public static AutoRemediationPolicy fixtureAppDownShadow(String policyId, Instant expiresAt) {
        return new AutoRemediationPolicy(SCHEMA_VERSION, policyId, AutonomyLevel.A3_SHADOW,
            FIXTURE_ENVIRONMENT, FIXTURE_TARGET, FIXTURE_CAPABILITY_PROFILE,
            RESTART_SERVICE, ORDER_API, 1, 100, expiresAt, true);
    }

    /** Stable hash that binds all policy fields used by a Shadow decision. */
    public String policyHash() {
        String canonical = schemaVersion + "\n" + policyId + "\n" + autonomyLevel + "\n"
            + environment + "\n" + targetId + "\n" + capabilityProfile + "\n"
            + actionCode + "\n" + serviceId + "\n" + maxAttempts + "\n"
            + maxShadowDecisions + "\n" + expiresAt.toEpochMilli() + "\n" + enabled;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for Shadow policy", e);
        }
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(Objects.requireNonNull(now, "now"));
    }

    private static void requireId(String value, String name) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]{2,62}")) {
            throw new IllegalArgumentException(name + " must match [a-z0-9][a-z0-9-]{2,62}");
        }
    }
}
