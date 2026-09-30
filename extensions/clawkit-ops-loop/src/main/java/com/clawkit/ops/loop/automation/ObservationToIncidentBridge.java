package com.clawkit.ops.loop.automation;

import com.clawkit.ops.loop.DiscoveryResult;
import com.clawkit.ops.loop.DiscoveryStatus;
import com.clawkit.ops.loop.Evidence;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges read-only Observation (Discovery) to the persistent Incident Registry.
 *
 * <h3>Classification rules (deterministic only)</h3>
 * <ul>
 *   <li>{@link ObservedSignal#APP_DOWN} — service/container stopped/unhealthy
 *       AND HTTP probe returned non-200. Creates or merges an ACTIVE Incident.</li>
 *   <li>{@link ObservedSignal#HEALTHY} — all required services healthy and
 *       HTTP probe returns 200. Does NOT create an Incident.</li>
 *   <li>{@link ObservedSignal#UNKNOWN} — evidence insufficient, collection
 *       failed, or transport lost. Does NOT close existing Incidents.</li>
 * </ul>
 *
 * <p>This bridge never calls FixSession, OpsFixSession, RepairOrchestrator,
 * opsfix, or any write-capable session. It only reads Discovery results and
 * writes to the Registry.
 */
public final class ObservationToIncidentBridge {

    private static final Logger log = LoggerFactory.getLogger(ObservationToIncidentBridge.class);

    private final IncidentRegistry registry;
    private final Clock clock;

    public ObservationToIncidentBridge(IncidentRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    /**
     * Process a discovery result: classify the observation and register if warranted.
     *
     * @param discovery   the completed discovery result
     * @param targetId    the explicit stable target identifier from the scheduler configuration
     * @param serviceId   the affected service (e.g. "order-api")
     * @return the bridge result with signal, classification, and registration outcome
     */
    public BridgeResult process(
        DiscoveryResult discovery,
        String targetId,
        String serviceId
    ) throws IOException {
        if (discovery == null) {
            throw new IllegalArgumentException("discovery must not be null");
        }
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }

        List<Evidence> evidence = discovery.bundle() != null
            ? discovery.bundle().evidence() : List.of();
        Instant now = clock.instant();

        // Only classify COMPLETE discoveries — everything else is UNKNOWN
        ObservedSignal signal;
        if (discovery.status() == DiscoveryStatus.COMPLETE) {
            signal = classify(evidence, now, serviceId);
        } else {
            signal = ObservedSignal.UNKNOWN;
        }

        IncidentFingerprint fingerprint = IncidentFingerprint.compute(
            targetId,
            serviceId,
            discovery.profileName(),
            ObservedSignal.APP_DOWN,
            IncidentFingerprint.CURRENT_CLASSIFIER_VERSION);

        // HEALTHY → no incident; a definitive recovery closes a matching ACTIVE incident.
        if (signal == ObservedSignal.HEALTHY) {
            if (!registry.tryAcquireTarget(targetId)) {
                return BridgeResult.skippedTargetBusy(targetId);
            }
            try {
                boolean recovered = registry.closeIfActive(fingerprint);
                log.debug("Bridge: HEALTHY — recovered={} (target={}, run={})",
                    recovered, targetId, discovery.runId());
                return BridgeResult.healthy(
                    discovery.runId(), evidenceReferences(evidence), recovered);
            } finally {
                registry.releaseTarget(targetId);
            }
        }

        // UNKNOWN → no incident, but don't close existing
        if (signal == ObservedSignal.UNKNOWN) {
            log.debug("Bridge: UNKNOWN — no incident created, existing unchanged (target={}, run={})",
                targetId, discovery.runId());
            return BridgeResult.unknown(
                discovery.runId(), evidenceReferences(evidence));
        }

        // APP_DOWN → create or merge incident
        // Try to acquire target exclusion
        if (!registry.tryAcquireTarget(targetId)) {
            log.warn("Bridge: target already acquired — skipping (target={})", targetId);
            return BridgeResult.skippedTargetBusy(targetId);
        }

        try {
            String incidentId = "inc-" + targetId + "-"
                + UUID.randomUUID().toString().substring(0, 8);
            List<String> refs = evidenceReferences(evidence);

            IncidentRegistry.RegistrationResult reg =
                registry.register(fingerprint, incidentId, discovery.runId(), refs);

            log.info("Bridge: APP_DOWN registered — incident={} isNew={} fp={}",
                reg.incidentId(), reg.isNewIncident(),
                fingerprint.hash().substring(0, 12));

            return BridgeResult.incidentCreated(
                signal, fingerprint, reg, discovery.runId(), refs);
        } finally {
            registry.releaseTarget(targetId);
        }
    }

    // ── Classification ────────────────────────────────────────────

    /**
     * Deterministic classification from current evidence only.
     *
     * <p>APP_DOWN requires BOTH:
     * <ol>
     *   <li>the requested service or container in stopped/exited/down/unhealthy state</li>
     *   <li>HTTP probe returned a non-200 status code</li>
     * </ol>
     *
     * <p>HEALTHY requires: all probed services healthy AND HTTP returns 200.
     *
     * <p>Everything else is UNKNOWN. Never uses model NL, confidence, or log bodies.
     */
    static ObservedSignal classify(List<Evidence> evidence, Instant evaluatedAt) {
        return classify(evidence, evaluatedAt, "order-api");
    }

    static ObservedSignal classify(
        List<Evidence> evidence,
        Instant evaluatedAt,
        String serviceId
    ) {
        // Filter by collection status (not fact.success — which has
        // evidence-type-specific meaning, e.g. HTTP probe status code).
        List<Evidence> current = evidence.stream()
            .filter(e -> e.collectionStatus() == Evidence.CollectionStatus.OBSERVED)
            .filter(e -> e.freshness() == Evidence.Freshness.CURRENT)
            .filter(e -> e.isCurrentAt(evaluatedAt))
            .toList();

        if (current.isEmpty()) {
            return ObservedSignal.UNKNOWN;
        }

        boolean requestedServiceDown = false;
        boolean httpFailing = false;
        boolean allHealthy = true;
        boolean requestedServiceObserved = false;
        boolean httpProbeObserved = false;

        for (Evidence item : current) {
            var data = item.fact().path("data");
            switch (item.type()) {
                case SERVICE_STATUS, CONTAINER_STATUS -> {
                    String state = data.path("State").asText("");
                    if (item.scope().contains(serviceId)) {
                        requestedServiceObserved = true;
                        if (!state.isBlank() && (state.contains("exited")
                            || state.contains("stopped") || state.contains("down")
                            || state.contains("unhealthy"))) {
                            requestedServiceDown = true;
                            allHealthy = false;
                        }
                    } else {
                        // Other services — check health
                        if (!state.isBlank() && (state.contains("exited")
                            || state.contains("stopped") || state.contains("down")
                            || state.contains("unhealthy"))) {
                            allHealthy = false;
                        }
                    }
                }
                case HTTP_PROBE -> {
                    httpProbeObserved = true;
                    int sc = data.path("statusCode").asInt(-1);
                    if (sc <= 0) sc = data.path("status").asInt(-1);
                    if (sc > 0 && sc != 200) {
                        httpFailing = true;
                        allHealthy = false;
                    }
                }
                default -> {}
            }
        }

        if (requestedServiceDown && httpFailing) {
            return ObservedSignal.APP_DOWN;
        }

        if (requestedServiceObserved && httpProbeObserved && allHealthy) {
            return ObservedSignal.HEALTHY;
        }

        return ObservedSignal.UNKNOWN;
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static List<String> evidenceReferences(List<Evidence> evidence) {
        return evidence.stream()
            .map(Evidence::rawReference)
            .filter(ref -> ref != null && !ref.isBlank())
            .toList();
    }

    // ── Result type ────────────────────────────────────────────────

    public sealed interface BridgeResult {
        /** The outcome kind. */
        Outcome outcome();

        enum Outcome {
            INCIDENT_CREATED,
            INCIDENT_MERGED,
            COOLDOWN_SKIPPED,
            HEALTHY,
            HEALTHY_RECOVERED,
            UNKNOWN,
            SKIPPED_TARGET_BUSY
        }

        record IncidentCreated(
            ObservedSignal signal,
            IncidentFingerprint fingerprint,
            IncidentRegistry.RegistrationResult registration,
            String runId,
            List<String> evidenceRefs
        ) implements BridgeResult {
            @Override public Outcome outcome() {
                if (registration.isNewIncident()) return Outcome.INCIDENT_CREATED;
                if (registration.isCooldownSkipped()) return Outcome.COOLDOWN_SKIPPED;
                return Outcome.INCIDENT_MERGED;
            }
        }

        record Healthy(String runId, List<String> evidenceRefs, boolean recovered) implements BridgeResult {
            @Override public Outcome outcome() {
                return recovered ? Outcome.HEALTHY_RECOVERED : Outcome.HEALTHY;
            }
        }

        record Unknown(String runId, List<String> evidenceRefs) implements BridgeResult {
            @Override public Outcome outcome() { return Outcome.UNKNOWN; }
        }

        record SkippedTargetBusy(String targetId) implements BridgeResult {
            @Override public Outcome outcome() { return Outcome.SKIPPED_TARGET_BUSY; }
        }

        static BridgeResult incidentCreated(
            ObservedSignal signal, IncidentFingerprint fp,
            IncidentRegistry.RegistrationResult reg, String runId, List<String> refs) {
            return new IncidentCreated(signal, fp, reg, runId, refs);
        }

        static BridgeResult healthy(String runId, List<String> refs, boolean recovered) {
            return new Healthy(runId, refs, recovered);
        }

        static BridgeResult unknown(String runId, List<String> refs) {
            return new Unknown(runId, refs);
        }

        static BridgeResult skippedTargetBusy(String targetId) {
            return new SkippedTargetBusy(targetId);
        }
    }
}
