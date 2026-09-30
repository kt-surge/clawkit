package com.clawkit.ops.loop.automation;

import com.clawkit.ops.loop.DiscoveryResult;
import com.clawkit.ops.loop.DiscoveryStatus;
import com.clawkit.ops.loop.Evidence;
import com.clawkit.ops.loop.EvidenceBundle;
import com.clawkit.ops.loop.EvidenceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accelerated equivalent of 72 hourly cycles against the durable scheduler
 * state and registry files. The fixture has no transport, provider, or write
 * capability; it emits only deterministic evidence objects.
 */
class AutomationSoakTest {

    @TempDir Path tempDir;

    @Test
    void accelerated72HourFixtureSoakCoversRecoveryFlappingPauseRestartAndBudgets()
        throws Exception {
        MutableTestClock clock = new MutableTestClock(
            Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC);
        Path registryPath = tempDir.resolve("incident-registry.jsonl");
        Path statePath = tempDir.resolve("automation-state.json");
        FixtureObservationRunner fixture = new FixtureObservationRunner(clock);
        CountingDiagnosisRunner diagnosis = new CountingDiagnosisRunner();

        IncidentRegistry registry = new IncidentRegistry(registryPath, clock,
            IncidentCooldownPolicy.FIXTURE);
        AutomationStateStore state = new AutomationStateStore(statePath, clock);
        state.setBudgetPolicy(new AutomationBudgetPolicy(1, Duration.ofHours(6), 3, 0));
        ManualAutomationTaskScheduler scheduler = new ManualAutomationTaskScheduler();
        ObservationAutomationCoordinator coordinator = coordinator(
            scheduler, state, fixture, registry, diagnosis, clock);
        coordinator.start();
        coordinator.scheduleTarget("fixture-order-api");

        for (int hour = 0; hour < 72; hour++) {
            if (hour == 20) coordinator.pauseTarget("fixture-order-api");
            if (hour == 24) coordinator.resumeTarget("fixture-order-api");
            if (hour == 30) {
                String persistedIncident = registry.snapshot().get(0).incidentId();
                coordinator.close();
                state.close();
                registry.close();

                registry = new IncidentRegistry(registryPath, clock, IncidentCooldownPolicy.FIXTURE);
                state = new AutomationStateStore(statePath, clock);
                scheduler = new ManualAutomationTaskScheduler();
                coordinator = coordinator(scheduler, state, fixture, registry, diagnosis, clock);
                coordinator.start();
                assertThat(registry.snapshot()).singleElement()
                    .extracting(RegistryEntry::incidentId).isEqualTo(persistedIncident);
            }

            fixture.signal(signalAt(hour));
            scheduler.tick();
            clock.advance(Duration.ofHours(1));
        }

        AutomationStatus status = coordinator.getStatus("fixture-order-api");
        assertThat(status.requested()).isEqualTo(68);
        assertThat(status.started()).isEqualTo(35);
        assertThat(status.skipped()).isEqualTo(33);
        assertThat(status.completed()).isEqualTo(13);
        assertThat(status.merged()).isEqualTo(22);
        assertThat(status.failed()).isZero();
        assertThat(status.inFlightRunId()).isNull();

        assertThat(fixture.calls.get()).isEqualTo(35);
        assertThat(fixture.maxConcurrent.get()).isEqualTo(1);
        assertThat(fixture.evidenceReferences).hasSize(35 * 2);
        assertThat(fixture.evidenceReferences).allMatch(ref -> ref.startsWith("run://fixture-"));
        assertThat(diagnosis.calls.get()).isZero();
        assertThat(registry.activeCount()).isEqualTo(1);
        assertThat(registry.snapshot()).singleElement().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo(RegistryEntry.EntryStatus.ACTIVE);
            assertThat(entry.observationCount()).isEqualTo(11);
            assertThat(entry.lastEvidenceRefs()).isNotEmpty();
        });

        coordinator.close();
        state.close();
        registry.close();
    }

    private static ObservationAutomationCoordinator coordinator(
        ManualAutomationTaskScheduler scheduler,
        AutomationStateStore state,
        FixtureObservationRunner fixture,
        IncidentRegistry registry,
        CountingDiagnosisRunner diagnosis,
        MutableTestClock clock
    ) {
        return new ObservationAutomationCoordinator(scheduler, state, fixture,
            new ObservationToIncidentBridge(registry, clock), diagnosis, clock,
            "order-api", Duration.ZERO, Duration.ofHours(1), false);
    }

    private static FixtureSignal signalAt(int hour) {
        if (hour == 12 || hour == 36 || hour == 43) return FixtureSignal.HEALTHY;
        if (hour == 14 || hour == 38) return FixtureSignal.UNKNOWN;
        return FixtureSignal.APP_DOWN;
    }

    private enum FixtureSignal { APP_DOWN, HEALTHY, UNKNOWN }

    private static final class CountingDiagnosisRunner implements DiagnosisRunner {
        private final AtomicInteger calls = new AtomicInteger();

        @Override public DiagnosisResult diagnose(String targetId, DiscoveryResult discovery) {
            calls.incrementAndGet();
            return new DiagnosisResult("UNREACHABLE", true);
        }
    }

    private static final class FixtureObservationRunner implements ObservationRunner {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final MutableTestClock clock;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();
        private final List<String> evidenceReferences = new ArrayList<>();
        private FixtureSignal signal = FixtureSignal.APP_DOWN;

        private FixtureObservationRunner(MutableTestClock clock) { this.clock = clock; }

        void signal(FixtureSignal next) { signal = next; }

        @Override public DiscoveryResult observe(String targetId) {
            int concurrent = inFlight.incrementAndGet();
            maxConcurrent.updateAndGet(previous -> Math.max(previous, concurrent));
            try {
                int sequence = calls.incrementAndGet();
                String runId = "fixture-" + sequence;
                Instant now = clock.instant();
                List<Evidence> evidence = switch (signal) {
                    case APP_DOWN -> List.of(serviceEvidence(runId, "stopped", now), httpEvidence(runId, 503, now));
                    case HEALTHY -> List.of(serviceEvidence(runId, "running", now), httpEvidence(runId, 200, now));
                    case UNKNOWN -> List.of(serviceEvidence(runId, "running", now), httpEvidence(runId, 503, now));
                };
                evidenceReferences.addAll(evidence.stream().map(Evidence::rawReference).toList());
                EvidenceBundle bundle = new EvidenceBundle("fixture-incident", runId, now, evidence);
                return new DiscoveryResult("fixture-incident", runId, "FIXTURE_APP_DOWN_V1",
                    bundle, DiscoveryStatus.COMPLETE, 2, evidence.size(), now);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        private static Evidence serviceEvidence(String runId, String state, Instant now) {
            ObjectNode fact = MAPPER.createObjectNode();
            ObjectNode data = MAPPER.createObjectNode();
            data.put("State", state);
            fact.put("success", true);
            fact.set("data", data);
            return new Evidence("svc-" + runId, "fixture-incident", EvidenceType.SERVICE_STATUS,
                "fixture/service_status", now, now, "container/order-api", Evidence.Kind.FACT,
                fact, "run://" + runId + "/service", Evidence.Freshness.CURRENT,
                Evidence.Redaction.NONE, "fixture-1", Evidence.CollectionStatus.OBSERVED, null, null);
        }

        private static Evidence httpEvidence(String runId, int status, Instant now) {
            ObjectNode fact = MAPPER.createObjectNode();
            ObjectNode data = MAPPER.createObjectNode();
            data.put("statusCode", status);
            fact.put("success", true);
            fact.set("data", data);
            return new Evidence("http-" + runId, "fixture-incident", EvidenceType.HTTP_PROBE,
                "fixture/http_probe", now, now, "endpoint/order-api", Evidence.Kind.FACT,
                fact, "run://" + runId + "/http", Evidence.Freshness.CURRENT,
                Evidence.Redaction.NONE, "fixture-1", Evidence.CollectionStatus.OBSERVED, null, null);
        }
    }
}
