package com.clawkit.ops.loop.automation;

import java.time.Instant;

/** Read-only per-target snapshot of the active automation budget window. */
public record AutomationBudgetStatus(
    String targetId,
    int policyVersion,
    Instant windowStart,
    Instant windowEnd,
    int discoveryLimit,
    int discoveryConsumed,
    int discoveryRemaining,
    int providerLimit,
    int providerConsumed,
    int providerRemaining
) {
    public AutomationBudgetStatus {
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        if (policyVersion < 1) throw new IllegalArgumentException("policyVersion >= 1");
        if (windowStart == null || windowEnd == null || !windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException("budget window must be valid");
        }
        if (discoveryLimit < 0 || discoveryConsumed < 0 || discoveryRemaining < 0
            || providerLimit < 0 || providerConsumed < 0 || providerRemaining < 0) {
            throw new IllegalArgumentException("budget values must not be negative");
        }
    }
}
