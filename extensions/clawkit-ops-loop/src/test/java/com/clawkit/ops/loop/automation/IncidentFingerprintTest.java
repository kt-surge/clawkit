package com.clawkit.ops.loop.automation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentFingerprintTest {

    @Test
    void sameInputsProduceIdenticalFingerprint() {
        var fp1 = IncidentFingerprint.compute(
            "t1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);
        var fp2 = IncidentFingerprint.compute(
            "t1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);

        assertThat(fp1.hash()).isEqualTo(fp2.hash());
        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    void differentTargetIdProducesDifferentFingerprint() {
        var fp1 = IncidentFingerprint.compute(
            "t1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);
        var fp2 = IncidentFingerprint.compute(
            "t2", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);

        assertThat(fp1.hash()).isNotEqualTo(fp2.hash());
    }

    @Test
    void differentServiceIdProducesDifferentFingerprint() {
        var fp1 = IncidentFingerprint.compute(
            "t1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);
        var fp2 = IncidentFingerprint.compute(
            "t1", "gateway", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);

        assertThat(fp1.hash()).isNotEqualTo(fp2.hash());
    }

    @Test
    void differentSignalProducesDifferentFingerprint() {
        var fp1 = IncidentFingerprint.compute(
            "t1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);
        var fp2 = IncidentFingerprint.compute(
            "t1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.HEALTHY, 1);

        assertThat(fp1.hash()).isNotEqualTo(fp2.hash());
    }

    @Test
    void differentClassifierVersionProducesDifferentFingerprint() {
        var fp1 = IncidentFingerprint.compute(
            "t1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);
        var fp2 = IncidentFingerprint.compute(
            "t1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 2);

        assertThat(fp1.hash()).isNotEqualTo(fp2.hash());
    }

    @Test
    void reconstructSucceedsWithCorrectHash() {
        var original = IncidentFingerprint.compute(
            "t1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);

        var reconstructed = IncidentFingerprint.reconstruct(
            "t1", "order-api", "REMOTE_APP_DOWN_V1",
            "APP_DOWN", 1, original.hash());

        assertThat(reconstructed).isEqualTo(original);
    }

    @Test
    void reconstructFailsWithWrongHash() {
        assertThatThrownBy(() -> IncidentFingerprint.reconstruct(
            "t1", "order-api", "REMOTE_APP_DOWN_V1",
            "APP_DOWN", 1, "0000badhash"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hash mismatch");
    }

    @Test
    void hashIs64HexCharacters() {
        var fp = IncidentFingerprint.compute(
            "t1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);

        assertThat(fp.hash()).hasSize(64);
        assertThat(fp.hash()).matches("[0-9a-f]{64}");
    }

    @Test
    void blankTargetIdRejected() {
        assertThatThrownBy(() -> IncidentFingerprint.compute(
            "", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankServiceIdRejected() {
        assertThatThrownBy(() -> IncidentFingerprint.compute(
            "t1", "", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classifierVersionMustBePositive() {
        assertThatThrownBy(() -> IncidentFingerprint.compute(
            "t1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
