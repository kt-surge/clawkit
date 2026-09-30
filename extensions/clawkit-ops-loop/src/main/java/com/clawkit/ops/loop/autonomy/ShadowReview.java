package com.clawkit.ops.loop.autonomy;

import java.time.Instant;
import java.util.Objects;

/** Immutable human review bound to one persisted zero-side-effect Shadow decision. */
public record ShadowReview(
    String reviewId,
    String decisionId,
    String policyHash,
    String evidenceSnapshotHash,
    ShadowReviewDecision reviewerDecision,
    Instant reviewedAt,
    int sideEffectCalls
) {
    public ShadowReview {
        if (reviewId == null || !reviewId.matches("shadow-review-[0-9a-f]{32}")) {
            throw new IllegalArgumentException("reviewId must be a deterministic Shadow review id");
        }
        if (decisionId == null || !decisionId.matches("shadow-[0-9a-f]{32}")) {
            throw new IllegalArgumentException("decisionId must be a deterministic Shadow id");
        }
        if (policyHash == null || !policyHash.matches("[0-9a-f]{64}")
            || evidenceSnapshotHash == null || !evidenceSnapshotHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("review must bind policy and evidence SHA-256 values");
        }
        reviewerDecision = Objects.requireNonNull(reviewerDecision, "reviewerDecision");
        reviewedAt = Objects.requireNonNull(reviewedAt, "reviewedAt");
        if (sideEffectCalls != 0) throw new IllegalArgumentException("Shadow review must record zero sideEffectCalls");
    }

    public static ShadowReview from(ShadowDecision decision, ShadowReviewDecision reviewerDecision, Instant reviewedAt) {
        Objects.requireNonNull(decision, "decision");
        String canonical = decision.decisionId() + "\n" + decision.policyHash() + "\n"
            + decision.evidenceSnapshotHash() + "\n" + reviewerDecision;
        return new ShadowReview("shadow-review-" + Hashing.sha256(canonical).substring(0, 32),
            decision.decisionId(), decision.policyHash(), decision.evidenceSnapshotHash(), reviewerDecision,
            reviewedAt, 0);
    }
}
