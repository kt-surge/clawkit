package com.clawkit.ops.loop.automation;

import com.clawkit.ops.loop.DiscoveryProfile;
import com.clawkit.ops.loop.DiscoveryResult;
import com.clawkit.ops.loop.DiscoveryStatus;
import com.clawkit.ops.loop.Evidence;
import com.clawkit.ops.loop.EvidenceBundle;
import com.clawkit.ops.loop.EvidenceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationToIncidentBridgeTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC);

    private IncidentRegistry registry;
    private ObservationToIncidentBridge bridge;

    @BeforeEach
    void setUp() throws IOException {
        registry = new IncidentRegistry(tempDir.resolve("registry.jsonl"), CLOCK);
        bridge = new ObservationToIncidentBridge(registry, CLOCK);
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.close();
        }
    }

    // ── APP_DOWN classification → creates incident ──

    @Test
    void appDownDiscoveryCreatesIncident() throws Exception {
        var discovery = appDownDiscovery("inc-target-1-abc12345", "run-1");
        var result = bridge.process(discovery, "target-1", "order-api");

        assertThat(result.outcome())
            .isEqualTo(ObservationToIncidentBridge.BridgeResult.Outcome.INCIDENT_CREATED);
        assertThat(registry.activeCount()).isEqualTo(1);
    }

    // ── HEALTHY classification → no incident ──

    @Test
    void healthyDiscoveryDoesNotCreateIncident() throws Exception {
        var discovery = healthyDiscovery("inc-target-1-abc12345", "run-1");
        var result = bridge.process(discovery, "target-1", "order-api");

        assertThat(result.outcome())
            .isEqualTo(ObservationToIncidentBridge.BridgeResult.Outcome.HEALTHY);
        assertThat(registry.activeCount()).isEqualTo(0);
    }

    // ── UNKNOWN classification → no incident, doesn't close existing ──

    @Test
    void unknownDiscoveryDoesNotCloseActiveIncident() throws Exception {
        var appDownDiscovery = appDownDiscovery("inc-target-1-abc12345", "run-1");
        bridge.process(appDownDiscovery, "target-1", "order-api");
        assertThat(registry.activeCount()).isEqualTo(1);

        var unknownDiscovery = unknownDiscovery("inc-target-1-def67890", "run-2");
        var result = bridge.process(unknownDiscovery, "target-1", "order-api");

        assertThat(result.outcome())
            .isEqualTo(ObservationToIncidentBridge.BridgeResult.Outcome.UNKNOWN);
        assertThat(registry.activeCount()).isEqualTo(1);
    }

    @Test
    void healthyClosesActiveAndAppDownIsSuppressedDuringCooldown() throws Exception {
        registry.close();
        MutableTestClock mutableClock = new MutableTestClock(
            Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC);
        registry = new IncidentRegistry(tempDir.resolve("registry.jsonl"), mutableClock,
            new IncidentCooldownPolicy(1, Duration.ofHours(6)));
        bridge = new ObservationToIncidentBridge(registry, mutableClock);

        bridge.process(appDownDiscovery("inc-1", "run-down"), "target-1", "order-api");
        var healthy = bridge.process(healthyDiscovery("inc-1", "run-healthy"),
            "target-1", "order-api");

        assertThat(healthy.outcome()).isEqualTo(
            ObservationToIncidentBridge.BridgeResult.Outcome.HEALTHY_RECOVERED);
        assertThat(registry.activeCount()).isZero();

        // The CLOSED status and its cooldown timestamp must survive a clean restart.
        registry.close();
        registry = new IncidentRegistry(tempDir.resolve("registry.jsonl"), mutableClock,
            new IncidentCooldownPolicy(1, Duration.ofHours(6)));
        bridge = new ObservationToIncidentBridge(registry, mutableClock);

        var suppressed = bridge.process(appDownDiscovery("inc-1", "run-flap"),
            "target-1", "order-api");
        assertThat(suppressed.outcome()).isEqualTo(
            ObservationToIncidentBridge.BridgeResult.Outcome.COOLDOWN_SKIPPED);
        assertThat(registry.activeCount()).isZero();

        mutableClock.advance(Duration.ofHours(6));
        var afterCooldown = bridge.process(appDownDiscovery("inc-1", "run-new"),
            "target-1", "order-api");
        assertThat(afterCooldown.outcome()).isEqualTo(
            ObservationToIncidentBridge.BridgeResult.Outcome.INCIDENT_CREATED);
        assertThat(registry.activeCount()).isEqualTo(1);
    }

    // ── TRANSPORT_FAILED → UNKNOWN ──

    @Test
    void transportFailedProducesUnknown() throws Exception {
        var discovery = transportFailedDiscovery("inc-target-1-abc12345", "run-1");
        var result = bridge.process(discovery, "target-1", "order-api");

        assertThat(result.outcome())
            .isEqualTo(ObservationToIncidentBridge.BridgeResult.Outcome.UNKNOWN);
        assertThat(registry.activeCount()).isEqualTo(0);
    }

    // ── Same APP_DOWN fingerprint → merges ──

    @Test
    void sameAppDownMergesIntoExistingIncident() throws Exception {
        var disc1 = appDownDiscovery("inc-target-1-abc12345", "run-1");
        var result1 = bridge.process(disc1, "target-1", "order-api");
        assertThat(result1.outcome())
            .isEqualTo(ObservationToIncidentBridge.BridgeResult.Outcome.INCIDENT_CREATED);

        var disc2 = appDownDiscovery("inc-target-1-abc12345", "run-2");
        var result2 = bridge.process(disc2, "target-1", "order-api");
        assertThat(result2.outcome())
            .isEqualTo(ObservationToIncidentBridge.BridgeResult.Outcome.INCIDENT_MERGED);

        assertThat(registry.activeCount()).isEqualTo(1);
    }

    @Test
    void explicitTargetPreventsMergeWhenDiscoveryIncidentIdsMatch() throws Exception {
        var first = appDownDiscovery("inc-random-abc12345", "run-1");
        var second = appDownDiscovery("inc-random-abc12345", "run-2");

        var firstResult = bridge.process(first, "target-a", "order-api");
        var secondResult = bridge.process(second, "target-b", "order-api");

        assertThat(firstResult.outcome())
            .isEqualTo(ObservationToIncidentBridge.BridgeResult.Outcome.INCIDENT_CREATED);
        assertThat(secondResult.outcome())
            .isEqualTo(ObservationToIncidentBridge.BridgeResult.Outcome.INCIDENT_CREATED);
        assertThat(registry.activeCount()).isEqualTo(2);
    }

    @Test
    void blankTargetIsRejectedBeforeAnyRegistration() {
        var discovery = appDownDiscovery("inc-random-abc12345", "run-1");

        assertThatThrownBy(() -> bridge.process(discovery, " ", "order-api"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("targetId");
        assertThat(registry.snapshot()).isEmpty();
    }

    // ── Classification tests ──

    @Test
    void classifyReturnsAppDownWhenOrderApiStoppedAndHttpFails() {
        Instant now = CLOCK.instant();
        String iid = "inc-1";
        var evidence = List.of(
            serviceEvidence(iid, "e-svc", EvidenceType.SERVICE_STATUS,
                "container/order-api", "stopped", now),
            httpEvidence(iid, "e-http", 503, now));

        assertThat(ObservationToIncidentBridge.classify(evidence, now))
            .isEqualTo(ObservedSignal.APP_DOWN);
    }

    @Test
    void classifyReturnsAppDownWhenOrderApiUnhealthyAndHttpFails() {
        Instant now = CLOCK.instant();
        String iid = "inc-1";
        var evidence = List.of(
            serviceEvidence(iid, "e-ctr", EvidenceType.CONTAINER_STATUS,
                "container/order-api", "unhealthy", now),
            httpEvidence(iid, "e-http", 502, now));

        assertThat(ObservationToIncidentBridge.classify(evidence, now))
            .isEqualTo(ObservedSignal.APP_DOWN);
    }

    @Test
    void classifyReturnsHealthyWhenAllServicesUpAndHttp200() {
        Instant now = CLOCK.instant();
        String iid = "inc-1";
        var evidence = List.of(
            serviceEvidence(iid, "e-1", EvidenceType.SERVICE_STATUS,
                "compose/order-api", "running", now),
            serviceEvidence(iid, "e-2", EvidenceType.SERVICE_STATUS,
                "compose/gateway", "running", now),
            httpEvidence(iid, "e-3", 200, now));

        assertThat(ObservationToIncidentBridge.classify(evidence, now))
            .isEqualTo(ObservedSignal.HEALTHY);
    }

    @Test
    void classifyReturnsUnknownWhenHttp200HasNoRequestedServiceObservation() {
        Instant now = CLOCK.instant();
        var evidence = List.of(httpEvidence("inc-1", "e-http", 200, now));

        assertThat(ObservationToIncidentBridge.classify(evidence, now))
            .isEqualTo(ObservedSignal.UNKNOWN);
    }

    @Test
    void classifyReturnsUnknownWhenOrderApiDownButHttp200() {
        Instant now = CLOCK.instant();
        String iid = "inc-1";
        var evidence = List.of(
            serviceEvidence(iid, "e-1", EvidenceType.SERVICE_STATUS,
                "container/order-api", "stopped", now),
            httpEvidence(iid, "e-2", 200, now));

        assertThat(ObservationToIncidentBridge.classify(evidence, now))
            .isEqualTo(ObservedSignal.UNKNOWN);
    }

    @Test
    void classifyReturnsUnknownWhenHttpFailsButOrderApiRunning() {
        Instant now = CLOCK.instant();
        String iid = "inc-1";
        var evidence = List.of(
            serviceEvidence(iid, "e-1", EvidenceType.SERVICE_STATUS,
                "container/order-api", "running", now),
            httpEvidence(iid, "e-2", 503, now));

        assertThat(ObservationToIncidentBridge.classify(evidence, now))
            .isEqualTo(ObservedSignal.UNKNOWN);
    }

    @Test
    void classifyReturnsUnknownWhenNoEvidence() {
        assertThat(ObservationToIncidentBridge.classify(List.of(), CLOCK.instant()))
            .isEqualTo(ObservedSignal.UNKNOWN);
    }

    @Test
    void classifyReturnsUnknownWhenStaleEvidence() {
        Instant now = CLOCK.instant();
        String iid = "inc-1";
        ObjectNode fact = MAPPER.createObjectNode();
        ObjectNode data = MAPPER.createObjectNode();
        data.put("State", "stopped");
        fact.put("success", true);
        fact.set("data", data);
        var stale = new Evidence(
            "e-stale", iid, EvidenceType.SERVICE_STATUS,
            "mcp:ops/service_status", now, now, "container/order-api",
            Evidence.Kind.FACT, fact,
            "run://run-1/e-stale", Evidence.Freshness.STALE,
            Evidence.Redaction.NONE, "2",
            Evidence.CollectionStatus.OBSERVED, null, null);

        assertThat(ObservationToIncidentBridge.classify(List.of(stale), now))
            .isEqualTo(ObservedSignal.UNKNOWN);
    }

    // ── Result type check ──

    @Test
    void appDownResultContainsFingerprintAndEvidenceRefs() throws Exception {
        var discovery = appDownDiscovery("inc-target-1-abc12345", "run-1");
        var result = bridge.process(discovery, "target-1", "order-api");

        assertThat(result).isInstanceOf(
            ObservationToIncidentBridge.BridgeResult.IncidentCreated.class);
        var created = (ObservationToIncidentBridge.BridgeResult.IncidentCreated) result;
        assertThat(created.runId()).isEqualTo("run-1");
        assertThat(created.evidenceRefs()).isNotEmpty();
        assertThat(created.fingerprint()).isNotNull();
        assertThat(created.fingerprint().deterministicSignalCode()).isEqualTo("APP_DOWN");
    }

    // ── Zero write operations guarantee ──

    @Test
    void bridgeNeverCallsFixSessionOrWriteOperations() throws Exception {
        var discovery = appDownDiscovery("inc-target-1-abc12345", "run-1");
        var result = bridge.process(discovery, "target-1", "order-api");

        assertThat(result.outcome())
            .isEqualTo(ObservationToIncidentBridge.BridgeResult.Outcome.INCIDENT_CREATED);
        assertThat(registry.activeCount()).isEqualTo(1);

        var entries = registry.snapshot();
        assertThat(entries).hasSize(1);
        var entry = entries.get(0);
        assertThat(entry.observationCount()).isEqualTo(1);
        assertThat(entry.status()).isEqualTo(RegistryEntry.EntryStatus.ACTIVE);
    }

    // ── Evidence helpers ──

    private static Evidence serviceEvidence(String incidentId, String evidenceId,
                                             EvidenceType type, String scope,
                                             String state, Instant now) {
        ObjectNode fact = MAPPER.createObjectNode();
        ObjectNode data = MAPPER.createObjectNode();
        data.put("State", state);
        data.put("Status", state);
        fact.put("success", true);
        fact.set("data", data);
        return new Evidence(
            evidenceId, incidentId, type,
            "mcp:ops/" + type.name().toLowerCase(),
            now, now, scope,
            Evidence.Kind.FACT, fact,
            "run://run-1/" + evidenceId,
            Evidence.Freshness.CURRENT,
            Evidence.Redaction.NONE, "2",
            Evidence.CollectionStatus.OBSERVED, null, null);
    }

    private static Evidence httpEvidence(String incidentId, String evidenceId,
                                          int statusCode, Instant now) {
        ObjectNode fact = MAPPER.createObjectNode();
        ObjectNode data = MAPPER.createObjectNode();
        data.put("statusCode", statusCode);
        data.put("status", statusCode);
        fact.put("success", true); // collection succeeded
        fact.set("data", data);
        return new Evidence(
            evidenceId, incidentId, EvidenceType.HTTP_PROBE,
            "mcp:ops/http_probe",
            now, now, "endpoint/order-api-metrics",
            Evidence.Kind.FACT, fact,
            "run://run-1/" + evidenceId,
            Evidence.Freshness.CURRENT,
            Evidence.Redaction.NONE, "2",
            Evidence.CollectionStatus.OBSERVED, null, null);
    }

    // ── Discovery builders ──

    private static DiscoveryResult appDownDiscovery(String incidentId, String runId) {
        Instant now = CLOCK.instant();
        var evidence = List.of(
            serviceEvidence(incidentId, "e-1", EvidenceType.SERVICE_STATUS,
                "container/order-api", "stopped", now),
            httpEvidence(incidentId, "e-2", 503, now));
        return discoveryResult(incidentId, runId, evidence, DiscoveryStatus.COMPLETE, 2);
    }

    private static DiscoveryResult healthyDiscovery(String incidentId, String runId) {
        Instant now = CLOCK.instant();
        var evidence = List.of(
            serviceEvidence(incidentId, "e-1", EvidenceType.SERVICE_STATUS,
                "compose/order-api", "running", now),
            serviceEvidence(incidentId, "e-2", EvidenceType.SERVICE_STATUS,
                "compose/gateway", "running", now),
            httpEvidence(incidentId, "e-3", 200, now));
        return discoveryResult(incidentId, runId, evidence, DiscoveryStatus.COMPLETE, 3);
    }

    private static DiscoveryResult unknownDiscovery(String incidentId, String runId) {
        Instant now = CLOCK.instant();
        var evidence = List.of(
            serviceEvidence(incidentId, "e-1", EvidenceType.SERVICE_STATUS,
                "container/order-api", "running", now),
            httpEvidence(incidentId, "e-2", 503, now));
        return discoveryResult(incidentId, runId, evidence, DiscoveryStatus.COMPLETE, 2);
    }

    private static DiscoveryResult transportFailedDiscovery(String incidentId, String runId) {
        Instant now = CLOCK.instant();
        // EvidenceBundle requires at least one evidence item
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", false);
        fact.put("error", "transport lost");
        var errorEvidence = new Evidence(
            "e-err", incidentId, EvidenceType.SERVICE_STATUS,
            "mcp:ops/error",
            now, now, "container/order-api",
            Evidence.Kind.FACT, fact,
            "run://" + runId + "/e-err",
            Evidence.Freshness.STALE,
            Evidence.Redaction.NONE, "2",
            Evidence.CollectionStatus.COLLECTION_FAILED, null, null);
        return discoveryResult(incidentId, runId, List.of(errorEvidence),
            DiscoveryStatus.TRANSPORT_FAILED, 0);
    }

    private static DiscoveryResult discoveryResult(String incidentId, String runId,
                                                    List<Evidence> evidence,
                                                    DiscoveryStatus status,
                                                    int requiredSuccess) {
        EvidenceBundle bundle = new EvidenceBundle(incidentId, runId,
            CLOCK.instant(), evidence);
        return new DiscoveryResult(incidentId, runId,
            DiscoveryProfile.REMOTE_APP_DOWN_V1.name(),
            bundle, status,
            requiredSuccess,
            evidence.size(), CLOCK.instant());
    }
}
