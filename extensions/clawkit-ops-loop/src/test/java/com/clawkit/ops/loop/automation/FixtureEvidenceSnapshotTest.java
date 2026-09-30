package com.clawkit.ops.loop.automation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureEvidenceSnapshotTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-09-19T08:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private FixtureObserveOnlyLoop loop;

    @AfterEach
    void closeLoop() {
        if (loop != null) loop.close();
    }

    @Test
    void createsAndVerifiesAnImmutableFixtureEvidenceBundle() throws Exception {
        ManualAutomationTaskScheduler scheduler = new ManualAutomationTaskScheduler();
        loop = new FixtureObserveOnlyLoop(new FixtureObserveOnlyLoop.Config(
            tempDir, "fixture-app-down", "order-api", Duration.ZERO, Duration.ofSeconds(1),
            AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY), CLOCK, scheduler);
        loop.start();
        scheduler.tick(1);
        loop.close();
        loop = null;

        var created = FixtureEvidenceSnapshot.create(tempDir, CLOCK);

        assertThat(created.snapshotId()).startsWith("fixture-snapshot-");
        assertThat(created.targetId()).isEqualTo("fixture-app-down");
        assertThat(created.timelineEvents()).isEqualTo(1);
        assertThat(created.directory()).isDirectory();
        assertThat(created.directory()).isDirectoryContaining(path ->
            path.getFileName().toString().equals("fixture-evidence-manifest.json"));
        assertThat(created.files()).containsOnlyKeys(
            "automation-state.json", "incident-registry.jsonl", "observation-timeline.jsonl");

        var verified = FixtureEvidenceSnapshot.verify(created.directory());
        assertThat(verified.snapshotId()).isEqualTo(created.snapshotId());
        assertThat(verified.files()).isEqualTo(created.files());
        assertThat(verified.createdAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void verificationRejectsTimelineChangedAfterSnapshotCreation() throws Exception {
        ManualAutomationTaskScheduler scheduler = new ManualAutomationTaskScheduler();
        loop = new FixtureObserveOnlyLoop(new FixtureObserveOnlyLoop.Config(
            tempDir, "fixture-app-down", "order-api", Duration.ZERO, Duration.ofSeconds(1),
            AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY), CLOCK, scheduler);
        loop.start();
        scheduler.tick(1);
        loop.close();
        loop = null;

        var created = FixtureEvidenceSnapshot.create(tempDir, CLOCK);
        Files.writeString(created.directory().resolve("observation-timeline.jsonl"),
            System.lineSeparator() + "{}", java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(() -> FixtureEvidenceSnapshot.verify(created.directory()))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("hash mismatch")
            .hasMessageContaining("observation-timeline.jsonl");
    }

    @Test
    void verificationRejectsAnyCompanionOtherThanTheRecognizedSoakReport() throws Exception {
        ManualAutomationTaskScheduler scheduler = new ManualAutomationTaskScheduler();
        loop = new FixtureObserveOnlyLoop(new FixtureObserveOnlyLoop.Config(
            tempDir, "fixture-app-down", "order-api", Duration.ZERO, Duration.ofSeconds(1),
            AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY), CLOCK, scheduler);
        loop.start();
        scheduler.tick(1);
        loop.close();
        loop = null;

        var created = FixtureEvidenceSnapshot.create(tempDir, CLOCK);
        Files.writeString(created.directory().resolve("unexpected.txt"), "not evidence");

        assertThatThrownBy(() -> FixtureEvidenceSnapshot.verify(created.directory()))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("only the optional accelerated soak report");
    }

    @Test
    void creationRequiresAllThreeEvidenceFiles() {
        assertThatThrownBy(() -> FixtureEvidenceSnapshot.create(tempDir, CLOCK))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("automation-state.json");
    }

    @Test
    void creationRejectsCorruptStateBeforePublishingAManifest() throws Exception {
        ManualAutomationTaskScheduler scheduler = new ManualAutomationTaskScheduler();
        loop = new FixtureObserveOnlyLoop(new FixtureObserveOnlyLoop.Config(
            tempDir, "fixture-app-down", "order-api", Duration.ZERO, Duration.ofSeconds(1),
            AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY), CLOCK, scheduler);
        loop.start();
        scheduler.tick(1);
        loop.close();
        loop = null;
        Files.writeString(tempDir.resolve("automation-state.json"), " ",
            java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(() -> FixtureEvidenceSnapshot.create(tempDir, CLOCK))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("state checksum");
    }

    @Test
    void creationRejectsCorruptRegistryBeforePublishingAManifest() throws Exception {
        ManualAutomationTaskScheduler scheduler = new ManualAutomationTaskScheduler();
        loop = new FixtureObserveOnlyLoop(new FixtureObserveOnlyLoop.Config(
            tempDir, "fixture-app-down", "order-api", Duration.ZERO, Duration.ofSeconds(1),
            AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY), CLOCK, scheduler);
        loop.start();
        scheduler.tick(1);
        loop.close();
        loop = null;
        Files.writeString(tempDir.resolve("incident-registry.jsonl"), " ",
            java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(() -> FixtureEvidenceSnapshot.create(tempDir, CLOCK))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("registry checksum");
    }
}
