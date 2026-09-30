package com.clawkit.ops.loop.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureAcceleratedSoakRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void emitsAReproducibleReportAndBoundSnapshotFromTheRealFixtureLoop() throws Exception {
        var result = FixtureAcceleratedSoakRunner.run(tempDir);

        assertThat(result.report().passed()).isTrue();
        assertThat(result.report().mode()).isEqualTo("ACCELERATED_LOGICAL_TIME");
        assertThat(result.report().logicalHours()).isEqualTo(72);
        assertThat(result.report().providerConsumed()).isZero();
        assertThat(result.report().providerLimit()).isZero();
        assertThat(result.report().timelineEvents())
            .isEqualTo(result.report().counts().completed() + result.report().counts().merged());
        assertThat(result.reportPath()).isRegularFile();
        assertThat(result.reportPath().getParent()).isEqualTo(result.snapshot().directory());
        assertThat(Files.readString(result.reportPath()))
            .contains("\"schema\":\"fixture-accelerated-soak-report\"")
            .contains("# SHA-256: ");
        assertThat(FixtureAcceleratedSoakRunner.verifyReport(result.reportPath()))
            .isEqualTo(result.report());
        assertThat(FixtureEvidenceSnapshot.verify(result.snapshot().directory()).snapshotId())
            .isEqualTo(result.report().snapshotId());

        Files.writeString(result.reportPath(), " ",
            java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> FixtureEvidenceSnapshot.verify(result.snapshot().directory()))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("checksum footer");
    }
}
