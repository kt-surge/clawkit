package com.clawkit.ops.loop.autonomy;

/** Model input to a Shadow judgement. It cannot relax deterministic policy. */
public enum ModelOpinion {
    SUPPORTS_ACTION,
    OPPOSES_ACTION,
    UNSPECIFIED
}
