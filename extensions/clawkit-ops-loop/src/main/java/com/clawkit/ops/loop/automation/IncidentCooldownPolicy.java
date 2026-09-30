package com.clawkit.ops.loop.automation;

import java.time.Duration;

/**
 * Versioned cooldown policy for a recovered incident fingerprint.
 *
 * <p>A cooldown suppresses a new incident only after a deterministic HEALTHY
 * observation has closed the prior ACTIVE incident. It never suppresses an
 * ACTIVE incident merge.
 */
public record IncidentCooldownPolicy(int policyVersion, Duration window) {

    /** Compatibility default: a caller must explicitly opt into cooldown. */
    public static final IncidentCooldownPolicy DISABLED =
        new IncidentCooldownPolicy(1, Duration.ZERO);

    /** Fixture-only policy used by the accelerated soak. */
    public static final IncidentCooldownPolicy FIXTURE =
        new IncidentCooldownPolicy(1, Duration.ofHours(6));

    public IncidentCooldownPolicy {
        if (policyVersion < 1) {
            throw new IllegalArgumentException("policyVersion must be >= 1");
        }
        if (window == null || window.isNegative()) {
            throw new IllegalArgumentException("window must not be negative");
        }
    }
}
