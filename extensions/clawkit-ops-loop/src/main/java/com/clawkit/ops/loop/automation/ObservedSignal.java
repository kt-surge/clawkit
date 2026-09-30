package com.clawkit.ops.loop.automation;

/**
 * Deterministic signal codes produced by observing read-only Discovery results.
 *
 * <p>These are computed from structured evidence only — never from model natural
 * language, confidence scores, log bodies, or timestamps.
 *
 * <p>Only {@link #APP_DOWN} creates or merges an Incident. {@link #HEALTHY}
 * creates nothing. {@link #UNKNOWN} never closes an active Incident.
 */
public enum ObservedSignal {
    /** Service/container stopped or unhealthy AND HTTP probe returned non-200. */
    APP_DOWN,
    /** All required services running and healthy, HTTP probe returns 200. */
    HEALTHY,
    /** Evidence insufficient, collection failed, or transport lost — cannot classify. */
    UNKNOWN
}
