package com.clawkit.ops.loop.autonomy;

/** A3 records a counterfactual judgement, never a dispatch instruction. */
public enum ShadowOutcome {
    ELIGIBLE_SHADOW,
    ASK_REQUIRED,
    REJECTED,
    EXPIRED
}
