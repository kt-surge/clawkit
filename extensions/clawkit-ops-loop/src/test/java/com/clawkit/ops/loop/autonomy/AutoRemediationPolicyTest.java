package com.clawkit.ops.loop.autonomy;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class AutoRemediationPolicyTest {
    private static final Instant EXPIRES = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    void fixtureShadowPolicyHasStableHash() {
        var first = AutoRemediationPolicy.fixtureAppDownShadow("fixture-shadow-v1", EXPIRES);
        var second = AutoRemediationPolicy.fixtureAppDownShadow("fixture-shadow-v1", EXPIRES);

        assertThat(first.autonomyLevel()).isEqualTo(AutonomyLevel.A3_SHADOW);
        assertThat(first.policyHash()).matches("[0-9a-f]{64}").isEqualTo(second.policyHash());
    }

    @Test
    void nonFixtureOrAutoPolicyIsRejectedBeforeA4Review() {
        assertThatThrownBy(() -> new AutoRemediationPolicy(1, "fixture-shadow-v1",
            AutonomyLevel.A4_LIMITED_AUTO, "FIXTURE", "fixture-app-down", "fixture-observe-only",
            "restart_service", "order-api", 1, 100, EXPIRES, true))
            .hasMessageContaining("A3_SHADOW");
        assertThatThrownBy(() -> new AutoRemediationPolicy(1, "fixture-shadow-v1",
            AutonomyLevel.A3_SHADOW, "PRODUCTION", "fixture-app-down", "fixture-observe-only",
            "restart_service", "order-api", 1, 100, EXPIRES, true))
            .hasMessageContaining("FIXTURE");
    }

    @Test
    void policyHashChangesWhenBoundPolicyFieldChanges() {
        var first = AutoRemediationPolicy.fixtureAppDownShadow("fixture-shadow-v1", EXPIRES);
        var disabled = new AutoRemediationPolicy(1, "fixture-shadow-v1", AutonomyLevel.A3_SHADOW,
            "FIXTURE", "fixture-app-down", "fixture-observe-only", "restart_service", "order-api",
            1, 100, EXPIRES, false);

        assertThat(disabled.policyHash()).isNotEqualTo(first.policyHash());
    }
}
