package com.clawkit.ops.loop.automation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable, deterministic fingerprint for incident deduplication.
 *
 * <p>Computed from canonical fields only:
 * <ul>
 *   <li>{@code targetId} — which server</li>
 *   <li>{@code serviceId} — which service on that server</li>
 *   <li>{@code discoveryProfile} — which evidence collection profile</li>
 *   <li>{@code deterministicSignalCode} — the observed signal (e.g. APP_DOWN)</li>
 *   <li>{@code classifierVersion} — version of the classification logic</li>
 * </ul>
 *
 * <p>Never includes: model natural language, confidence scores, log bodies,
 * timestamps, runId, or random incident IDs.
 */
public record IncidentFingerprint(
    String targetId,
    String serviceId,
    String discoveryProfile,
    String deterministicSignalCode,
    int classifierVersion,
    String hash
) {
    private static final String DELIMITER = "|";
    /** Bump when the classification rules change incompatibly. */
    public static final int CURRENT_CLASSIFIER_VERSION = 1;

    public IncidentFingerprint {
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
        if (discoveryProfile == null || discoveryProfile.isBlank()) {
            throw new IllegalArgumentException("discoveryProfile must not be blank");
        }
        if (deterministicSignalCode == null || deterministicSignalCode.isBlank()) {
            throw new IllegalArgumentException("deterministicSignalCode must not be blank");
        }
        if (classifierVersion < 1) {
            throw new IllegalArgumentException("classifierVersion must be >= 1");
        }
        if (hash == null || hash.isBlank()) {
            throw new IllegalArgumentException("hash must not be blank");
        }
    }

    /**
     * Compute a fingerprint from the canonical fields.
     *
     * @param targetId          the target server identifier
     * @param serviceId         the affected service (e.g. "order-api")
     * @param discoveryProfile  the discovery profile used (e.g. "REMOTE_APP_DOWN_V1")
     * @param signal            the deterministic observed signal
     * @param classifierVersion version of the classification rules
     * @return a fingerprint with SHA-256 hash
     */
    public static IncidentFingerprint compute(
        String targetId,
        String serviceId,
        String discoveryProfile,
        ObservedSignal signal,
        int classifierVersion
    ) {
        String canonical = String.join(DELIMITER,
            targetId, serviceId, discoveryProfile,
            signal.name(), String.valueOf(classifierVersion));
        String hash = sha256(canonical);
        return new IncidentFingerprint(
            targetId, serviceId, discoveryProfile,
            signal.name(), classifierVersion, hash);
    }

    /**
     * Reconstruct a fingerprint from persisted fields and verify the hash.
     *
     * @throws IllegalArgumentException if the hash does not match
     */
    public static IncidentFingerprint reconstruct(
        String targetId, String serviceId, String discoveryProfile,
        String deterministicSignalCode, int classifierVersion, String expectedHash
    ) {
        String canonical = String.join(DELIMITER,
            targetId, serviceId, discoveryProfile,
            deterministicSignalCode, String.valueOf(classifierVersion));
        String computed = sha256(canonical);
        if (!computed.equals(expectedHash)) {
            throw new IllegalArgumentException(
                "fingerprint hash mismatch: expected " + expectedHash + " but computed " + computed);
        }
        return new IncidentFingerprint(
            targetId, serviceId, discoveryProfile,
            deterministicSignalCode, classifierVersion, computed);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
