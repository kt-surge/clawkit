package com.clawkit.ops.loop.repair;

import java.io.IOException;

/**
 * Minimal write-capable session for executing an approved repair action.
 *
 * <p>Decouples {@link RepairOrchestrator} from the concrete
 * {@link OpsFixSession} so that fixture tests can inject a fake
 * session with write counters — without real SSH, without Mockito,
 * and without weakening any safety gate.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link OpsFixSession} — production: real SSH + MCP restart_service</li>
 *   <li>{@code FakeFixSession} — test fixture: counts calls, returns canned result</li>
 * </ul>
 */
public interface FixSession extends AutoCloseable {

    /**
     * Execute the allowlisted restart_service(order-api) action.
     *
     * @param incidentId the incident this repair belongs to
     * @param repairRunId unique identifier for this repair attempt
     * @return the repair result with attempt state and effect certainty
     * @throws IOException if the transport fails (treated as OUTCOME_UNKNOWN)
     */
    RepairResult executeRestart(String incidentId, String repairRunId) throws IOException;

    /** Gracefully close the session. Idempotent. */
    @Override
    void close();
}
