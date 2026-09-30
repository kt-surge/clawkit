package com.clawkit.ops.loop.automation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomationStateStoreTest {

    @TempDir
    Path tempDir;

    private Path statePath;
    private Clock clock;
    private AutomationStateStore store;

    @BeforeEach
    void setUp() throws IOException {
        statePath = tempDir.resolve("state.json");
        clock = Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC);
        store = new AutomationStateStore(statePath, clock);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    // ── Basic operations ──

    @Test
    void initialStateIsNotPaused() {
        assertThat(store.isGlobalPaused()).isFalse();
        assertThat(store.isTargetPaused("t1")).isFalse();
    }

    @Test
    void pauseAndResumePersist() throws Exception {
        store.setGlobalPaused(true);
        store.setTargetPaused("t1", true);

        store.close();
        store = new AutomationStateStore(statePath, clock);

        assertThat(store.isGlobalPaused()).isTrue();
        assertThat(store.isTargetPaused("t1")).isTrue();
        assertThat(store.isTargetPaused("t2")).isFalse();
    }

    @Test
    void budgetPolicyPersists() throws Exception {
        var policy = new AutomationBudgetPolicy(2, Duration.ofMinutes(5), 10, 2);
        store.setBudgetPolicy(policy);

        store.close();
        store = new AutomationStateStore(statePath, clock);

        var loaded = store.budgetPolicy();
        assertThat(loaded.policyVersion()).isEqualTo(2);
        assertThat(loaded.window()).isEqualTo(Duration.ofMinutes(5));
        assertThat(loaded.maxDiscoveryRuns()).isEqualTo(10);
        assertThat(loaded.maxProviderCalls()).isEqualTo(2);
    }

    @Test
    void budgetConsumptionPersists() throws Exception {
        store.setBudgetPolicy(new AutomationBudgetPolicy(1,
            Duration.ofHours(1), 5, 3));
        assertThat(store.tryConsumeDiscovery("t1")).isTrue();
        assertThat(store.tryConsumeDiscovery("t1")).isTrue();
        assertThat(store.tryConsumeProvider("t1")).isTrue();

        store.close();
        store = new AutomationStateStore(statePath, clock);

        var ts = store.ensureTarget("t1");
        assertThat(ts.discoveryConsumed()).isEqualTo(2);
        assertThat(ts.providerConsumed()).isEqualTo(1);
    }

    @Test
    void newBudgetWindowResetsBothCountersRegardlessOfWhichBudgetIsConsumedFirst()
        throws Exception {
        store.setBudgetPolicy(new AutomationBudgetPolicy(1,
            Duration.ofHours(1), 1, 1));
        assertThat(store.tryConsumeDiscovery("t1")).isTrue();
        assertThat(store.tryConsumeProvider("t1")).isTrue();
        store.close();

        clock = Clock.fixed(Instant.parse("2026-08-04T02:00:00Z"), ZoneOffset.UTC);
        store = new AutomationStateStore(statePath, clock);

        // Provider-first used to retain the previous window's discovery count.
        assertThat(store.tryConsumeProvider("t1")).isTrue();
        assertThat(store.tryConsumeDiscovery("t1")).isTrue();
    }

    @Test
    void zeroBudgetBlocksConsumption() throws Exception {
        store.setBudgetPolicy(new AutomationBudgetPolicy(1,
            Duration.ofHours(1), 0, 0));
        assertThat(store.tryConsumeDiscovery("t1")).isFalse();
        assertThat(store.tryConsumeProvider("t1")).isFalse();
    }

    @Test
    void countersIncrement() throws Exception {
        store.setTargetPaused("t1", false);
        store.setInFlight("t1", "run-1");
        store.clearInFlight("t1");

        var status = store.status("t1");
        assertThat(status.inFlightRunId()).isNull();
    }

    @Test
    void recoverAbandonedMarksInFlight() throws Exception {
        store.setInFlight("t1", "run-abc");
        store.close();

        store = new AutomationStateStore(statePath, clock);
        int abandoned = store.recoverAbandoned();
        assertThat(abandoned).isEqualTo(1);

        var status = store.status("t1");
        assertThat(status.lastOutcome()).isEqualTo("ABANDONED_AFTER_RESTART");
        assertThat(status.failed()).isEqualTo(1);
        assertThat(status.inFlightRunId()).isNull();
    }

    // ── Test 13: State file corruption → fail-closed ──

    @Test
    void tailTruncationFailsClosed() throws Exception {
        store.setGlobalPaused(true);
        store.close();

        String content = Files.readString(statePath, StandardCharsets.UTF_8);
        // Truncate footer
        String truncated = content.substring(0, content.indexOf("# SHA-256:"));
        Files.writeString(statePath, truncated, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> {
            var s = new AutomationStateStore(statePath, clock);
            s.close();
        }).isInstanceOf(IOException.class)
            .hasMessageContaining("footer missing");
    }

    @Test
    void contentAfterChecksumFooterFailsClosed() throws Exception {
        store.close();
        Files.writeString(statePath, "UNTRUSTED_TRAILER\n", StandardCharsets.UTF_8,
            StandardOpenOption.APPEND);

        assertThatThrownBy(() -> {
            var s = new AutomationStateStore(statePath, clock);
            s.close();
        }).isInstanceOf(IOException.class)
            .hasMessageContaining("footer missing or malformed");
    }

    @Test
    void midStreamCorruptionFailsClosed() throws Exception {
        store.setGlobalPaused(true);
        store.close();

        String content = Files.readString(statePath, StandardCharsets.UTF_8);
        String corrupted = content.replace("\"globalPaused\":true", "\"globalPaused\":false");
        Files.writeString(statePath, corrupted, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> {
            var s = new AutomationStateStore(statePath, clock);
            s.close();
        }).isInstanceOf(IOException.class)
            .hasMessageContaining("checksum mismatch");
    }

    @Test
    void corruptHeaderFailsClosed() throws Exception {
        store.close();
        writePayload("NOT VALID JSON {{{");

        assertThatThrownBy(() -> {
            var s = new AutomationStateStore(statePath, clock);
            s.close();
        }).isInstanceOf(IOException.class)
            .hasMessageContaining("payload is corrupt");
    }

    @Test
    void unknownSchemaFailsClosed() throws Exception {
        store.setGlobalPaused(true);
        store.close();

        String content = Files.readString(statePath, StandardCharsets.UTF_8);
        String payload = content.lines().findFirst().orElseThrow()
            .replace("\"schema\":\"automation-state\"", "\"schema\":\"evil-schema\"");
        writePayload(payload);

        assertThatThrownBy(() -> {
            var s = new AutomationStateStore(statePath, clock);
            s.close();
        }).isInstanceOf(IOException.class)
            .hasMessageContaining("Unknown state schema");
    }

    @Test
    void unknownVersionWithValidChecksumFailsClosed() throws Exception {
        store.close();
        String payload = Files.readString(statePath, StandardCharsets.UTF_8)
            .lines().findFirst().orElseThrow()
            .replace("\"version\":1", "\"version\":999");
        writePayload(payload);

        assertThatThrownBy(() -> {
            var s = new AutomationStateStore(statePath, clock);
            s.close();
        }).isInstanceOf(IOException.class)
            .hasMessageContaining("Unsupported state version");
    }

    @Test
    void missingRequiredFieldWithValidChecksumFailsClosed() throws Exception {
        store.close();
        String payload = Files.readString(statePath, StandardCharsets.UTF_8)
            .lines().findFirst().orElseThrow()
            .replace("\"globalPaused\":false,", "");
        writePayload(payload);

        assertThatThrownBy(() -> {
            var s = new AutomationStateStore(statePath, clock);
            s.close();
        }).isInstanceOf(IOException.class)
            .hasMessageContaining("payload fields are invalid");
    }

    @Test
    void dualInstanceFailsClosed() throws Exception {
        // First store holds lock
        assertThat(store).isNotNull();
        // Second attempt fails
        assertThatThrownBy(() -> {
            var s2 = new AutomationStateStore(statePath, clock);
            s2.close();
        }).isInstanceOf(IOException.class)
            .hasMessageContaining("Cannot lock");
    }

    private void writePayload(String payload) throws Exception {
        String checksum = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
        String content = payload + System.lineSeparator()
            + AutomationStateStore.FOOTER_PREFIX + checksum + System.lineSeparator();
        Files.writeString(statePath, content, StandardCharsets.UTF_8);
    }
}
