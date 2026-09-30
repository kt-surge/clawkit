package com.clawkit.ops.loop.automation;

import com.clawkit.ops.loop.autonomy.FixtureShadowReplayRunner;
import com.clawkit.ops.loop.autonomy.ShadowOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureShadowReplayRunnerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);

    @TempDir Path tempDir;

    @Test
    void verifiedFixtureSnapshotProducesOneDurableZeroSideEffectShadowReplay() throws Exception {
        var scheduler = new ManualAutomationTaskScheduler();
        var loop = new FixtureObserveOnlyLoop(new FixtureObserveOnlyLoop.Config(
            tempDir.resolve("observe"), "fixture-app-down", "order-api", Duration.ZERO,
            Duration.ofSeconds(1), AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY), CLOCK, scheduler);
        loop.start();
        scheduler.tick(1);
        loop.close();

        var snapshot = FixtureEvidenceSnapshot.create(tempDir.resolve("observe"), CLOCK);
        var first = FixtureShadowReplayRunner.run(snapshot.directory(), tempDir.resolve("shadow"), CLOCK);
        var replay = FixtureShadowReplayRunner.run(snapshot.directory(), tempDir.resolve("shadow"), CLOCK);

        assertThat(first.recordedDecision().created()).isTrue();
        assertThat(replay.recordedDecision().created()).isFalse();
        assertThat(first.recordedDecision().decision().outcome()).isEqualTo(ShadowOutcome.ELIGIBLE_SHADOW);
        assertThat(first.recordedDecision().decision().sideEffectCalls()).isZero();
        assertThat(first.directory().resolve("policies")).isDirectory();
        assertThat(first.directory().resolve("decisions")).isDirectory();
        try (var files = Files.list(snapshot.directory())) {
            assertThat(files.count()).isEqualTo(4);
        }
    }

    @Test
    void shadowReplayRefusesAnActiveRunWhoseBoundFactsDoNotShowAppDown() throws Exception {
        var scheduler = new ManualAutomationTaskScheduler();
        var loop = new FixtureObserveOnlyLoop(new FixtureObserveOnlyLoop.Config(
            tempDir.resolve("observe"), "fixture-app-down", "order-api", Duration.ZERO,
            Duration.ofSeconds(1), AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY), CLOCK, scheduler);
        loop.start();
        scheduler.tick(1);
        loop.close();

        var snapshot = FixtureEvidenceSnapshot.create(tempDir.resolve("observe"), CLOCK);
        Path timeline = snapshot.directory().resolve("observation-timeline.jsonl");
        Files.writeString(timeline, Files.readString(timeline).replace("\"value\":\"stopped\"",
            "\"value\":\"running\""));
        rebindTimeline(snapshot.directory());

        assertThatThrownBy(() -> FixtureShadowReplayRunner.run(
            snapshot.directory(), tempDir.resolve("shadow"), CLOCK))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("verified stopped service and HTTP 503 facts");
    }

    private static void rebindTimeline(Path snapshotDirectory) throws Exception {
        Path manifest = snapshotDirectory.resolve(FixtureEvidenceSnapshot.MANIFEST_FILE);
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.readTree(Files.readString(manifest).lines().findFirst().orElseThrow());
        byte[] timeline = Files.readAllBytes(snapshotDirectory.resolve("observation-timeline.jsonl"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("files")
            .path("observation-timeline.jsonl")).put("bytes", timeline.length)
            .put("sha256", java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(timeline)));
        String payload = mapper.writeValueAsString(root);
        String footer = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(payload.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(manifest, payload + "\n# SHA-256: " + footer + "\n");
    }
}
