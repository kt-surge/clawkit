package com.clawkit.ops.loop.automation;

import com.clawkit.ops.loop.DiscoveryResult;

/**
 * Runs one read-only observation cycle for a target.
 *
 * <p>Implementations must be read-only. Fake implementations for testing
 * return canned results. The production implementation is not part of this
 * phase.
 */
@FunctionalInterface
public interface ObservationRunner {
    /**
     * Execute one observation cycle.
     *
     * @param targetId the target to observe
     * @return the discovery result (never null)
     * @throws Exception if the observation fails
     */
    DiscoveryResult observe(String targetId) throws Exception;
}
