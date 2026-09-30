package com.clawkit.ops.loop.automation;

import com.clawkit.ops.loop.DiscoveryResult;
import com.clawkit.ops.loop.DiscoveryStatus;
import com.clawkit.ops.loop.Evidence;
import com.clawkit.ops.loop.EvidenceBundle;
import com.clawkit.ops.loop.EvidenceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory, read-only observation source for the OPS-3A Fixture loop.
 *
 * <p>It never opens a socket, loads a Provider, executes a tool, or creates a
 * repair session. The only evidence references it emits use the
 * {@code fixture://} scheme so a caller cannot mistake the result for a
 * remote observation.
 */
public final class FixtureObservationRunner implements ObservationRunner {

    public static final String DISCOVERY_PROFILE = "FIXTURE_APP_DOWN_V1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Clock clock;
    private final AtomicReference<ObservedSignal> signal =
        new AtomicReference<>(ObservedSignal.APP_DOWN);

    public FixtureObservationRunner(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Change only the next Fixture observations; this never affects a remote target. */
    public void setSignal(ObservedSignal nextSignal) {
        signal.set(Objects.requireNonNull(nextSignal, "nextSignal"));
    }

    public ObservedSignal signal() {
        return signal.get();
    }

    @Override
    public DiscoveryResult observe(String targetId) {
        if (targetId == null || !targetId.matches("fixture-[a-z0-9][a-z0-9_-]{0,62}")) {
            throw new IllegalArgumentException("Fixture observation requires a fixture-* targetId");
        }

        // The runner is reconstructed when the CLI restarts. A local counter
        // would therefore reuse fixture:// evidence references across process
        // lifetimes and make replay ambiguous. UUIDs keep every Fixture run
        // distinct without introducing a remote identity service.
        String runId = "fixture-observe-" + UUID.randomUUID();
        String incidentId = "fixture-incident-" + targetId;
        Instant now = clock.instant();
        ObservedSignal current = signal();

        List<Evidence> evidence = switch (current) {
            case APP_DOWN -> List.of(
                serviceEvidence(incidentId, runId, "stopped", now),
                httpEvidence(incidentId, runId, 503, now));
            case HEALTHY -> List.of(
                serviceEvidence(incidentId, runId, "running", now),
                httpEvidence(incidentId, runId, 200, now));
            case UNKNOWN -> List.of(collectionFailureEvidence(incidentId, runId, now));
        };

        DiscoveryStatus status = current == ObservedSignal.UNKNOWN
            ? DiscoveryStatus.INCOMPLETE : DiscoveryStatus.COMPLETE;
        EvidenceBundle bundle = new EvidenceBundle(incidentId, runId, now, evidence);
        int requiredSuccess = current == ObservedSignal.UNKNOWN ? 0 : evidence.size();
        return new DiscoveryResult(incidentId, runId, DISCOVERY_PROFILE, bundle,
            status, requiredSuccess, evidence.size(), now);
    }

    private static Evidence serviceEvidence(
        String incidentId, String runId, String state, Instant now
    ) {
        ObjectNode data = MAPPER.createObjectNode().put("State", state);
        ObjectNode fact = MAPPER.createObjectNode().put("success", true).set("data", data);
        return new Evidence("service-status", incidentId, EvidenceType.SERVICE_STATUS,
            "fixture:service_status", now, now, "container/order-api", Evidence.Kind.FACT,
            fact, "fixture://" + runId + "/service-status", Evidence.Freshness.CURRENT,
            Evidence.Redaction.NONE, "fixture-1", Evidence.CollectionStatus.OBSERVED,
            now.plus(Duration.ofMinutes(1)), null);
    }

    private static Evidence httpEvidence(
        String incidentId, String runId, int statusCode, Instant now
    ) {
        ObjectNode data = MAPPER.createObjectNode().put("statusCode", statusCode);
        ObjectNode fact = MAPPER.createObjectNode().put("success", true).set("data", data);
        return new Evidence("http-probe", incidentId, EvidenceType.HTTP_PROBE,
            "fixture:http_probe", now, now, "endpoint/order-api-metrics", Evidence.Kind.FACT,
            fact, "fixture://" + runId + "/http-probe", Evidence.Freshness.CURRENT,
            Evidence.Redaction.NONE, "fixture-1", Evidence.CollectionStatus.OBSERVED,
            now.plus(Duration.ofMinutes(1)), null);
    }

    private static Evidence collectionFailureEvidence(
        String incidentId, String runId, Instant now
    ) {
        ObjectNode fact = MAPPER.createObjectNode()
            .put("success", false)
            .put("errorCode", "FIXTURE_EVIDENCE_UNAVAILABLE")
            .set("data", MAPPER.createObjectNode());
        return new Evidence("collection-failure", incidentId, EvidenceType.SERVICE_STATUS,
            "fixture:collection_failure", now, now, "container/order-api", Evidence.Kind.FACT,
            fact, "fixture://" + runId + "/collection-failure", Evidence.Freshness.CURRENT,
            Evidence.Redaction.NONE, "fixture-1", Evidence.CollectionStatus.COLLECTION_FAILED,
            now.plus(Duration.ofMinutes(1)), null);
    }
}
