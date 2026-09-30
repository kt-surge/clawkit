package com.clawkit.ops.loop.autonomy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureShadowEvaluationRunnerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);

    @TempDir Path tempDir;

    @Test
    void writesAndReplaysAOneHundredCaseZeroSideEffectFixtureMatrix() throws Exception {
        var first = FixtureShadowEvaluationRunner.run(tempDir, CLOCK);
        var replay = FixtureShadowEvaluationRunner.run(tempDir, CLOCK);

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(first.report().counts()).isEqualTo(new FixtureShadowEvaluationRunner.Counts(100, 20, 47, 30, 3));
        assertThat(first.report().sideEffectCalls()).isZero();
        assertThat(first.report().passed()).isTrue();
        assertThat(first.report().reasonCounts()).containsEntry("CONDITION_NOT_ACTIVE", 15)
            .containsEntry("EVIDENCE_INSUFFICIENT", 8).containsEntry("REPAIR_POLICY_DENIED", 10);
        assertThat(new ShadowDecisionStore(tempDir.resolve("decisions")).list()).hasSize(100)
            .allMatch(decision -> decision.sideEffectCalls() == 0);
        assertThat(FixtureShadowEvaluationRunner.verifyReport(
            tempDir.resolve(FixtureShadowEvaluationRunner.REPORT_FILE))).isEqualTo(first.report());
    }

    @Test
    void tamperedEvaluationReportFailsClosed() throws Exception {
        FixtureShadowEvaluationRunner.run(tempDir, CLOCK);
        Path report = tempDir.resolve(FixtureShadowEvaluationRunner.REPORT_FILE);
        Files.writeString(report, "{}\n# SHA-256: " + "0".repeat(64) + "\n");

        assertThatThrownBy(() -> FixtureShadowEvaluationRunner.verifyReport(report))
            .hasMessageContaining("checksum");
    }
}
