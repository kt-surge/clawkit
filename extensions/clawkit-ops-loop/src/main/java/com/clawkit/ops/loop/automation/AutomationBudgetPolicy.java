package com.clawkit.ops.loop.automation;

import java.time.Duration;

/**
 * Immutable budget policy for automation.
 *
 * <p>All limits are per-target within a fixed time window. Zero means
 * "disallowed", not "unlimited". Discovery and provider budgets are separate.
 */
public record AutomationBudgetPolicy(
    int policyVersion,
    Duration window,
    int maxDiscoveryRuns,
    int maxProviderCalls
) {
    public AutomationBudgetPolicy {
        if (policyVersion < 1) throw new IllegalArgumentException("policyVersion >= 1");
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive");
        }
        if (maxDiscoveryRuns < 0) throw new IllegalArgumentException("maxDiscoveryRuns >= 0");
        if (maxProviderCalls < 0) throw new IllegalArgumentException("maxProviderCalls >= 0");
    }

    /** Budget that allows nothing — useful as a test baseline. */
    public static final AutomationBudgetPolicy ZERO = new AutomationBudgetPolicy(
        1, Duration.ofHours(1), 0, 0);

    /** Budget for fixture-only testing: moderate discovery, zero provider. */
    public static final AutomationBudgetPolicy FIXTURE_OBSERVE_ONLY = new AutomationBudgetPolicy(
        1, Duration.ofHours(1), 100, 0);

    @Override
    public String toString() {
        return "BudgetPolicy{v" + policyVersion + " w=" + window.toMinutes()
            + "m D=" + maxDiscoveryRuns + " P=" + maxProviderCalls + "}";
    }
}
