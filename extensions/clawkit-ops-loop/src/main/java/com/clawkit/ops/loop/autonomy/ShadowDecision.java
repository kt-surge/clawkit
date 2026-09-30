package com.clawkit.ops.loop.autonomy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Persistable result of an A3 counterfactual decision.
 *
 * <p>{@code sideEffectCalls} is a hard contract field: A3 must always emit zero.
 */
public record ShadowDecision(
    String decisionId,
    Instant decidedAt,
    String incidentId,
    String targetId,
    String evidenceSnapshotHash,
    String policyHash,
    String candidateAction,
    String candidateServiceId,
    ShadowOutcome outcome,
    List<String> reasonCodes,
    ModelOpinion modelOpinion,
    int sideEffectCalls
) {
    public ShadowDecision {
        requireNonBlank(decisionId, "decisionId");
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
        requireNonBlank(incidentId, "incidentId");
        requireNonBlank(targetId, "targetId");
        requireSha256(evidenceSnapshotHash, "evidenceSnapshotHash");
        requireSha256(policyHash, "policyHash");
        requireNonBlank(candidateAction, "candidateAction");
        requireNonBlank(candidateServiceId, "candidateServiceId");
        outcome = Objects.requireNonNull(outcome, "outcome");
        reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reasonCodes"));
        if (reasonCodes.isEmpty() || reasonCodes.stream().anyMatch(code -> code == null || code.isBlank())) {
            throw new IllegalArgumentException("reasonCodes must not be empty or blank");
        }
        modelOpinion = Objects.requireNonNull(modelOpinion, "modelOpinion");
        if (sideEffectCalls != 0) {
            throw new IllegalArgumentException("A3 Shadow must record zero sideEffectCalls");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex value");
        }
    }

    /**
     * Stable A3 identity. A rerun of the same evidence under the same policy
     * must replay one Shadow conclusion instead of creating a second candidate.
     */
    public static String deterministicId(String incidentId, String targetId, String evidenceSnapshotHash,
                                         String policyHash, String candidateAction, String candidateServiceId) {
        String canonical = String.join("\n", incidentId, targetId, evidenceSnapshotHash,
            policyHash, candidateAction, candidateServiceId);
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return "shadow-" + digest.substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for Shadow decisions", e);
        }
    }

    ShadowDecision withOutcome(ShadowOutcome replacementOutcome, String replacementReason) {
        return new ShadowDecision(decisionId, decidedAt, incidentId, targetId, evidenceSnapshotHash,
            policyHash, candidateAction, candidateServiceId, replacementOutcome,
            List.of(replacementReason), modelOpinion, 0);
    }
}
