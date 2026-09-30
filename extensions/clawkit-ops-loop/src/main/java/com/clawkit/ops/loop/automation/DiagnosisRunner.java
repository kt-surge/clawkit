package com.clawkit.ops.loop.automation;

import com.clawkit.ops.loop.DiscoveryResult;

/**
 * Runs diagnosis on a completed observation.
 *
 * <p>This is an injectable boundary. This phase only uses fake
 * implementations; a real Provider adapter is deferred.
 */
@FunctionalInterface
public interface DiagnosisRunner {
    /**
     * Run diagnosis on a completed observation.
     *
     * @param targetId  the target
     * @param discovery the completed discovery result to diagnose
     * @return diagnostic signals (never null)
     * @throws Exception if diagnosis fails
     */
    DiagnosisResult diagnose(String targetId, DiscoveryResult discovery) throws Exception;

    /** A diagnosis outcome. */
    record DiagnosisResult(String diagnosis, boolean providerCalled) {
        public DiagnosisResult {
            if (diagnosis == null || diagnosis.isBlank()) {
                throw new IllegalArgumentException("diagnosis must not be blank");
            }
        }
    }
}
