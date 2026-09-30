package com.clawkit.ops.loop.autonomy;

/**
 * OPS workflow autonomy, deliberately separate from a generic tool permission mode.
 */
public enum AutonomyLevel {
    A0_OBSERVE,
    A1_RECOMMEND,
    A2_ASK,
    A3_SHADOW,
    A4_LIMITED_AUTO
}
