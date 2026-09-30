package com.clawkit.ops.loop.autonomy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureAutonomyEvidenceMainTest {
    @TempDir Path tempDir;

    @Test
    void producesBoundA0AndZeroSideEffectA3EvidenceWithoutRemoteAccess() throws Exception {
        var result = FixtureAutonomyEvidenceMain.run(tempDir);

        assertThat(result.observe().report().passed()).isTrue();
        assertThat(result.observe().report().providerConsumed()).isZero();
        assertThat(result.shadow().recordedDecision().decision().sideEffectCalls()).isZero();
        assertThat(result.shadow().recordedDecision().decision().outcome()).isEqualTo(ShadowOutcome.ASK_REQUIRED);
        assertThat(result.shadow().recordedDecision().decision().reasonCodes()).containsExactly("EVIDENCE_STALE");
        assertThat(result.freshShadow().recordedDecision().decision().outcome())
            .isEqualTo(ShadowOutcome.ELIGIBLE_SHADOW);
        assertThat(result.freshShadow().recordedDecision().decision().sideEffectCalls()).isZero();
        assertThat(result.evaluation().report().counts().total()).isEqualTo(100);
        assertThat(result.evaluation().report().sideEffectCalls()).isZero();
        assertThat(result.observe().snapshot().directory().resolve("fixture-evidence-manifest.json")).isRegularFile();
        assertThat(result.shadow().directory().resolve("decisions")).isDirectory();
        assertThat(result.freshSnapshot().directory().resolve("fixture-evidence-manifest.json")).isRegularFile();
        assertThat(result.freshShadow().directory().resolve("decisions")).isDirectory();
        assertThat(result.evaluation().directory().resolve(FixtureShadowEvaluationRunner.REPORT_FILE)).isRegularFile();
    }
}
