package com.clawkit.ops.loop.automation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureObserveOnlyLoopTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-09-19T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private FixtureObserveOnlyLoop loop;

    @AfterEach
    void closeLoop() {
        if (loop != null) loop.close();
    }

    @Test
    void fixtureLoopDeduplicatesAndNeverConsumesProviderBudget() throws Exception {
        ManualAutomationTaskScheduler scheduler = new ManualAutomationTaskScheduler();
        loop = new FixtureObserveOnlyLoop(new FixtureObserveOnlyLoop.Config(
            tempDir, "fixture-app-down", "order-api", Duration.ZERO, Duration.ofSeconds(1),
            AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY), CLOCK, scheduler);

        loop.start();
        scheduler.tick(100);

        assertThat(loop.status().requested()).isEqualTo(100);
        assertThat(loop.status().merged()).isEqualTo(99);
        assertThat(loop.status().lastSkipReason())
            .isEqualTo("SKIPPED_ACTIVE_INCIDENT_MERGED");
        assertThat(loop.budgetStatus().providerConsumed()).isZero();
        assertThat(loop.incidents()).hasSize(1);
        assertThat(loop.incidents().getFirst().status())
            .isEqualTo(RegistryEntry.EntryStatus.ACTIVE);
        assertThat(loop.incidents().getFirst().lastEvidenceRefs())
            .allMatch(ref -> ref.startsWith("fixture://"));
    }

    @Test
    void healthyFixtureClosesActiveIncidentWithoutRepair() throws Exception {
        ManualAutomationTaskScheduler scheduler = new ManualAutomationTaskScheduler();
        loop = new FixtureObserveOnlyLoop(new FixtureObserveOnlyLoop.Config(
            tempDir, "fixture-app-down", "order-api", Duration.ZERO, Duration.ofSeconds(1),
            AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY), CLOCK, scheduler);

        loop.start();
        scheduler.tick(1);
        loop.setSignal(ObservedSignal.HEALTHY);
        scheduler.tick(1);

        assertThat(loop.status().lastOutcome()).isEqualTo("HEALTHY_RECOVERED");
        assertThat(loop.incidents().getFirst().status())
            .isEqualTo(RegistryEntry.EntryStatus.CLOSED);
        assertThat(loop.budgetStatus().providerConsumed()).isZero();
    }

    @Test
    void unknownFixturePersistsCollectionFailureEvidenceWithoutClosingIncident() throws Exception {
        ManualAutomationTaskScheduler scheduler = new ManualAutomationTaskScheduler();
        loop = new FixtureObserveOnlyLoop(new FixtureObserveOnlyLoop.Config(
            tempDir, "fixture-app-down", "order-api", Duration.ZERO, Duration.ofSeconds(1),
            AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY), CLOCK, scheduler);

        loop.start();
        scheduler.tick(1);
        loop.setSignal(ObservedSignal.UNKNOWN);
        scheduler.tick(1);

        assertThat(loop.status().lastOutcome()).isEqualTo("UNKNOWN");
        assertThat(loop.status().failed()).isZero();
        assertThat(loop.incidents()).singleElement().satisfies(incident ->
            assertThat(incident.status()).isEqualTo(RegistryEntry.EntryStatus.ACTIVE));
        assertThat(FixtureObservationTimelineReader.read(
            tempDir.resolve("observation-timeline.jsonl"), 10).getLast())
            .satisfies(entry -> {
                assertThat(entry.type()).isEqualTo("OBSERVATION_UNKNOWN");
                assertThat(entry.evidenceRefs()).singleElement()
                    .asString().endsWith("/collection-failure");
                assertThat(entry.providerCalled()).isFalse();
            });
    }

    @Test
    void fixtureLoopRecordsThatDiagnosisIsDisabledInsteadOfAProviderBudgetFailure() throws Exception {
        ManualAutomationTaskScheduler scheduler = new ManualAutomationTaskScheduler();
        loop = new FixtureObserveOnlyLoop(new FixtureObserveOnlyLoop.Config(
            tempDir, "fixture-app-down", "order-api", Duration.ZERO, Duration.ofSeconds(1),
            AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY), CLOCK, scheduler);

        loop.start();
        scheduler.tick(1);

        assertThat(loop.status().lastOutcome()).isEqualTo("INCIDENT_CREATED");
        assertThat(loop.status().lastSkipReason())
            .isEqualTo("DIAGNOSIS_DISABLED_OBSERVE_ONLY");
        assertThat(loop.budgetStatus().providerConsumed()).isZero();
    }

    @Test
    void fixtureLoopPersistsAnAppendOnlyObservationTimelineForReplay() throws Exception {
        ManualAutomationTaskScheduler scheduler = new ManualAutomationTaskScheduler();
        loop = new FixtureObserveOnlyLoop(new FixtureObserveOnlyLoop.Config(
            tempDir, "fixture-app-down", "order-api", Duration.ZERO, Duration.ofSeconds(1),
            AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY), CLOCK, scheduler);

        loop.start();
        scheduler.tick(1);
        loop.setSignal(ObservedSignal.HEALTHY);
        scheduler.tick(1);

        var entries = Files.readAllLines(tempDir.resolve("observation-timeline.jsonl"));
        assertThat(entries).hasSize(2);
        assertThat(entries.getFirst()).contains("OBSERVATION_INCIDENT_CREATED")
            .contains("fixture://fixture-observe-")
            .contains("/service-status")
            .contains("diagnosisEnabled\":false");
        assertThat(entries.getLast()).contains("OBSERVATION_HEALTHY_RECOVERED")
            .contains("fixture://fixture-observe-")
            .contains("/http-probe");
    }

    @Test
    void fixtureTimelineReaderReturnsOnlySafeReplayEntries() throws Exception {
        ManualAutomationTaskScheduler scheduler = new ManualAutomationTaskScheduler();
        loop = new FixtureObserveOnlyLoop(new FixtureObserveOnlyLoop.Config(
            tempDir, "fixture-app-down", "order-api", Duration.ZERO, Duration.ofSeconds(1),
            AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY), CLOCK, scheduler);

        loop.start();
        scheduler.tick(1);
        loop.setSignal(ObservedSignal.HEALTHY);
        scheduler.tick(1);

        var entries = FixtureObservationTimelineReader.read(
            tempDir.resolve("observation-timeline.jsonl"), 10);

        assertThat(entries).extracting(FixtureObservationTimelineReader.Entry::type)
            .containsExactly("OBSERVATION_INCIDENT_CREATED", "OBSERVATION_HEALTHY_RECOVERED");
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.targetId()).isEqualTo("fixture-app-down");
            assertThat(entry.diagnosisEnabled()).isFalse();
            assertThat(entry.providerCalled()).isFalse();
            assertThat(entry.evidenceRefs()).allMatch(ref -> ref.startsWith("fixture://"));
        });
        assertThat(FixtureObservationTimelineReader.read(
            tempDir.resolve("observation-timeline.jsonl"), 1))
            .extracting(FixtureObservationTimelineReader.Entry::type)
            .containsExactly("OBSERVATION_HEALTHY_RECOVERED");
    }

    @Test
    void fixtureTimelineReaderRejectsNonFixtureEvidence() throws Exception {
        Files.writeString(tempDir.resolve("observation-timeline.jsonl"), """
            {"schemaVersion":"1","at":"2026-09-19T00:00:00Z","type":"OBSERVATION_INCIDENT_CREATED","runEventReference":"run://fixture-observe-a89c8874-29ad-4d0a-bdf8-3e7f4b2a32c7","fields":{"targetId":"fixture-app-down","evidenceRefs":["file:///sensitive.log"],"diagnosisEnabled":false,"providerCalled":false}}
            """);

        assertThatThrownBy(() -> FixtureObservationTimelineReader.read(
            tempDir.resolve("observation-timeline.jsonl"), 10))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("unsafe evidence reference");
    }

    @Test
    void fixtureTimelineReaderRejectsUnknownObservationType() throws Exception {
        Files.writeString(tempDir.resolve("observation-timeline.jsonl"), """
            {"schemaVersion":"1","at":"2026-09-19T00:00:00Z","type":"OBSERVATION_UNREVIEWED_ACTION","runEventReference":"run://fixture-observe-a89c8874-29ad-4d0a-bdf8-3e7f4b2a32c7","fields":{"targetId":"fixture-app-down","evidenceRefs":["fixture://fixture-observe-a89c8874-29ad-4d0a-bdf8-3e7f4b2a32c7/service-status"],"diagnosisEnabled":false,"providerCalled":false}}
            """);

        assertThatThrownBy(() -> FixtureObservationTimelineReader.read(
            tempDir.resolve("observation-timeline.jsonl"), 10))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("unexpected type");
    }

    @Test
    void fixtureTimelineReaderRejectsEvidenceFromAnotherRun() throws Exception {
        Files.writeString(tempDir.resolve("observation-timeline.jsonl"), """
            {"schemaVersion":"1","at":"2026-09-19T00:00:00Z","type":"OBSERVATION_INCIDENT_CREATED","runEventReference":"run://fixture-observe-a89c8874-29ad-4d0a-bdf8-3e7f4b2a32c7","fields":{"targetId":"fixture-app-down","evidenceRefs":["fixture://fixture-observe-b90d9985-3abe-4e1b-9c07-4f805c3b43d8/service-status"],"diagnosisEnabled":false,"providerCalled":false}}
            """);

        assertThatThrownBy(() -> FixtureObservationTimelineReader.read(
            tempDir.resolve("observation-timeline.jsonl"), 10))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("evidence reference does not belong to event run");
    }

    @Test
    void fixtureTimelineReaderRejectsDuplicateRunReferences() throws Exception {
        Files.writeString(tempDir.resolve("observation-timeline.jsonl"), """
            {"schemaVersion":"1","at":"2026-09-19T00:00:00Z","type":"OBSERVATION_INCIDENT_CREATED","runEventReference":"run://fixture-observe-a89c8874-29ad-4d0a-bdf8-3e7f4b2a32c7","fields":{"targetId":"fixture-app-down","evidenceRefs":["fixture://fixture-observe-a89c8874-29ad-4d0a-bdf8-3e7f4b2a32c7/service-status"],"diagnosisEnabled":false,"providerCalled":false}}
            {"schemaVersion":"1","at":"2026-09-19T00:00:01Z","type":"OBSERVATION_INCIDENT_MERGED","runEventReference":"run://fixture-observe-a89c8874-29ad-4d0a-bdf8-3e7f4b2a32c7","fields":{"targetId":"fixture-app-down","evidenceRefs":["fixture://fixture-observe-a89c8874-29ad-4d0a-bdf8-3e7f4b2a32c7/http-probe"],"diagnosisEnabled":false,"providerCalled":false}}
            """);

        assertThatThrownBy(() -> FixtureObservationTimelineReader.read(
            tempDir.resolve("observation-timeline.jsonl"), 10))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("duplicate run reference");
    }

    @Test
    void fixtureTimelineReaderRejectsMixedTargets() throws Exception {
        Files.writeString(tempDir.resolve("observation-timeline.jsonl"), """
            {"schemaVersion":"1","at":"2026-09-19T00:00:00Z","type":"OBSERVATION_INCIDENT_CREATED","runEventReference":"run://fixture-observe-a89c8874-29ad-4d0a-bdf8-3e7f4b2a32c7","fields":{"targetId":"fixture-app-down","evidenceRefs":["fixture://fixture-observe-a89c8874-29ad-4d0a-bdf8-3e7f4b2a32c7/service-status"],"diagnosisEnabled":false,"providerCalled":false}}
            {"schemaVersion":"1","at":"2026-09-19T00:00:01Z","type":"OBSERVATION_HEALTHY","runEventReference":"run://fixture-observe-b90d9985-3abe-4e1b-9c07-4f805c3b43d8","fields":{"targetId":"fixture-other","evidenceRefs":["fixture://fixture-observe-b90d9985-3abe-4e1b-9c07-4f805c3b43d8/http-probe"],"diagnosisEnabled":false,"providerCalled":false}}
            """);

        assertThatThrownBy(() -> FixtureObservationTimelineReader.read(
            tempDir.resolve("observation-timeline.jsonl"), 10))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("mixed targets");
    }

    @Test
    void fixtureTimelineReaderRejectsDuplicateEvidenceReferences() throws Exception {
        Files.writeString(tempDir.resolve("observation-timeline.jsonl"), """
            {"schemaVersion":"1","at":"2026-09-19T00:00:00Z","type":"OBSERVATION_INCIDENT_CREATED","runEventReference":"run://fixture-observe-a89c8874-29ad-4d0a-bdf8-3e7f4b2a32c7","fields":{"targetId":"fixture-app-down","evidenceRefs":["fixture://fixture-observe-a89c8874-29ad-4d0a-bdf8-3e7f4b2a32c7/service-status","fixture://fixture-observe-a89c8874-29ad-4d0a-bdf8-3e7f4b2a32c7/service-status"],"diagnosisEnabled":false,"providerCalled":false}}
            """);

        assertThatThrownBy(() -> FixtureObservationTimelineReader.read(
            tempDir.resolve("observation-timeline.jsonl"), 10))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("duplicate evidence reference");
    }

    @Test
    void fixtureTimelineReaderRejectsBackwardsTimestamps() throws Exception {
        Files.writeString(tempDir.resolve("observation-timeline.jsonl"), """
            {"schemaVersion":"1","at":"2026-09-19T00:00:01Z","type":"OBSERVATION_INCIDENT_CREATED","runEventReference":"run://fixture-observe-a89c8874-29ad-4d0a-bdf8-3e7f4b2a32c7","fields":{"targetId":"fixture-app-down","evidenceRefs":["fixture://fixture-observe-a89c8874-29ad-4d0a-bdf8-3e7f4b2a32c7/service-status"],"diagnosisEnabled":false,"providerCalled":false}}
            {"schemaVersion":"1","at":"2026-09-19T00:00:00Z","type":"OBSERVATION_HEALTHY","runEventReference":"run://fixture-observe-b90d9985-3abe-4e1b-9c07-4f805c3b43d8","fields":{"targetId":"fixture-app-down","evidenceRefs":["fixture://fixture-observe-b90d9985-3abe-4e1b-9c07-4f805c3b43d8/http-probe"],"diagnosisEnabled":false,"providerCalled":false}}
            """);

        assertThatThrownBy(() -> FixtureObservationTimelineReader.read(
            tempDir.resolve("observation-timeline.jsonl"), 10))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("timestamp moved backwards");
    }

    @Test
    void restartKeepsFixtureRunReferencesUniqueWhileMergingTheActiveIncident() throws Exception {
        ManualAutomationTaskScheduler firstScheduler = new ManualAutomationTaskScheduler();
        loop = new FixtureObserveOnlyLoop(new FixtureObserveOnlyLoop.Config(
            tempDir, "fixture-app-down", "order-api", Duration.ZERO, Duration.ofSeconds(1),
            AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY), CLOCK, firstScheduler);
        loop.start();
        firstScheduler.tick(1);
        loop.close();
        loop = null;

        ManualAutomationTaskScheduler restartedScheduler = new ManualAutomationTaskScheduler();
        loop = new FixtureObserveOnlyLoop(new FixtureObserveOnlyLoop.Config(
            tempDir, "fixture-app-down", "order-api", Duration.ZERO, Duration.ofSeconds(1),
            AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY), CLOCK, restartedScheduler);
        loop.start();
        restartedScheduler.tick(1);

        var entries = FixtureObservationTimelineReader.read(
            tempDir.resolve("observation-timeline.jsonl"), 10);
        assertThat(entries).extracting(FixtureObservationTimelineReader.Entry::type)
            .containsExactly("OBSERVATION_INCIDENT_CREATED", "OBSERVATION_INCIDENT_MERGED");
        assertThat(entries).extracting(FixtureObservationTimelineReader.Entry::runEventReference)
            .doesNotHaveDuplicates();
        assertThat(loop.incidents()).singleElement().satisfies(incident -> {
            assertThat(incident.status()).isEqualTo(RegistryEntry.EntryStatus.ACTIVE);
            assertThat(incident.observationCount()).isEqualTo(2);
        });
        assertThat(loop.budgetStatus().providerConsumed()).isZero();
    }

    @Test
    void compositionRejectsRemoteTargetsAndProviderBudget() {
        assertThatThrownBy(() -> new FixtureObserveOnlyLoop.Config(
            tempDir, "production-target", "order-api", Duration.ZERO, Duration.ofSeconds(1),
            AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("fixture-");

        assertThatThrownBy(() -> new FixtureObserveOnlyLoop.Config(
            tempDir, "fixture-app-down", "order-api", Duration.ZERO, Duration.ofSeconds(1),
            new AutomationBudgetPolicy(1, Duration.ofHours(1), 10, 1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxProviderCalls=0");
    }
}
