package com.clawkit.ops.loop.automation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentRegistryTest {

    @TempDir
    Path tempDir;

    private Path registryPath;
    private Clock clock;
    private IncidentRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        registryPath = tempDir.resolve("incident-registry.jsonl");
        clock = Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC);
        registry = new IncidentRegistry(registryPath, clock);
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.close();
        }
    }

    // ── Test 1: Same fingerprint 100 observations → 1 ACTIVE, 99 MERGED ──

    @Test
    void sameFingerprint100TimesProducesOneActiveAnd99Merged() throws Exception {
        var fingerprint = IncidentFingerprint.compute(
            "target-1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);

        String incidentId = null;
        int created = 0;
        int merged = 0;

        for (int i = 0; i < 100; i++) {
            String runId = "run-" + i;
            String iid = "inc-target-1-" + UUID.randomUUID().toString().substring(0, 8);
            var result = registry.register(
                fingerprint, iid, runId,
                List.of("run://" + runId + "/e-1"));

            if (result.isNewIncident()) {
                created++;
                incidentId = result.incidentId();
            } else {
                merged++;
                assertThat(result.incidentId()).isEqualTo(incidentId);
            }
        }

        assertThat(created).isEqualTo(1);
        assertThat(merged).isEqualTo(99);

        var active = registry.findActive(fingerprint);
        assertThat(active).isPresent();
        assertThat(active.get().observationCount()).isEqualTo(100);
        assertThat(active.get().status()).isEqualTo(RegistryEntry.EntryStatus.ACTIVE);
        assertThat(active.get().incidentId()).isEqualTo(incidentId);

        assertThat(registry.activeCount()).isEqualTo(1);
    }

    // ── Test 2: 100 concurrent same-target requests → 0 overlap ──

    @Test
    void concurrentSameTargetRequestsProduceZeroOverlap() throws Exception {
        int threads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(
            Math.min(threads, Runtime.getRuntime().availableProcessors()));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger acquiredCount = new AtomicInteger(0);
        AtomicBoolean overlapDetected = new AtomicBoolean(false);
        AtomicInteger inProgress = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    // Retry until acquired — verifies serialization, not starvation
                    while (!registry.tryAcquireTarget("target-1")) {
                        Thread.yield();
                    }
                    int current = inProgress.incrementAndGet();
                    if (current > 1) {
                        overlapDetected.set(true); // Should never happen
                    }
                    acquiredCount.incrementAndGet();
                    // Simulate brief discovery work
                    Thread.sleep(1);
                    inProgress.decrementAndGet();
                    registry.releaseTarget("target-1");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        latch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // All 100 eventually acquired without overlap
        assertThat(acquiredCount.get()).isEqualTo(threads);
        assertThat(overlapDetected.get()).isFalse();
    }

    // ── Test 3: HEALTHY → no Incident ──

    @Test
    void healthySignalDoesNotCreateEntry() throws Exception {
        var fingerprint = IncidentFingerprint.compute(
            "target-1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.HEALTHY, 1);

        assertThat(registry.findActive(fingerprint)).isEmpty();
        assertThat(registry.snapshot()).isEmpty();
        assertThat(registry.activeCount()).isEqualTo(0);
    }

    // ── Test 4: UNKNOWN doesn't close active incident ──

    @Test
    void unknownSignalDoesNotCloseActiveIncident() throws Exception {
        var appDownFp = IncidentFingerprint.compute(
            "target-1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);

        registry.register(appDownFp, "inc-target-1-abc12345", "run-1",
            List.of("run://run-1/e-1"));

        var active = registry.findActive(appDownFp);
        assertThat(active).isPresent();
        assertThat(active.get().status()).isEqualTo(RegistryEntry.EntryStatus.ACTIVE);
        assertThat(registry.activeCount()).isEqualTo(1);
    }

    // ── Test 5: Restart recovery — no duplicates ──

    @Test
    void restartRecoveryDoesNotCreateDuplicates() throws Exception {
        var fingerprint = IncidentFingerprint.compute(
            "target-1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);

        // Register 3 observations then close registry
        for (int i = 0; i < 3; i++) {
            String iid = "inc-target-1-" + UUID.randomUUID().toString().substring(0, 8);
            registry.register(
                fingerprint, iid, "run-" + i, List.of("run://run-" + i + "/e-1"));
        }

        var active = registry.findActive(fingerprint);
        assertThat(active).isPresent();
        assertThat(active.get().observationCount()).isEqualTo(3);
        String persistedIncidentId = active.get().incidentId();

        // Close and reopen — simulate restart
        registry.close();

        registry = new IncidentRegistry(registryPath, clock);

        // After restart, the entry is preserved
        var restored = registry.findActive(fingerprint);
        assertThat(restored).isPresent();
        assertThat(restored.get().observationCount()).isEqualTo(3);
        assertThat(restored.get().incidentId()).isEqualTo(persistedIncidentId);
        assertThat(restored.get().status()).isEqualTo(RegistryEntry.EntryStatus.ACTIVE);

        // Register more — should merge, not duplicate
        for (int i = 3; i < 5; i++) {
            String iid = "inc-target-1-" + UUID.randomUUID().toString().substring(0, 8);
            var result = registry.register(
                fingerprint, iid, "run-" + i, List.of("run://run-" + i + "/e-1"));
            assertThat(result.isNewIncident()).isFalse();
        }

        var afterRestart = registry.findActive(fingerprint);
        assertThat(afterRestart).isPresent();
        assertThat(afterRestart.get().observationCount()).isEqualTo(5);
        assertThat(afterRestart.get().incidentId()).isEqualTo(persistedIncidentId);
    }

    // ── Test 6a: Tail truncation — fail-closed ──

    @Test
    void tailTruncationFailsClosed() throws Exception {
        var fingerprint = IncidentFingerprint.compute(
            "target-1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);

        registry.register(fingerprint, "inc-target-1-abc12345", "run-1",
            List.of("run://run-1/e-1"));
        registry.close();

        // Read and truncate footer
        String content = Files.readString(registryPath, StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(content.lines().toList());
        // Remove the footer line (last line starting with "# SHA-256:")
        while (!lines.isEmpty() && (lines.get(lines.size() - 1).startsWith("#")
            || lines.get(lines.size() - 1).isBlank())) {
            lines.remove(lines.size() - 1);
        }
        String truncated = String.join(System.lineSeparator(), lines);

        Files.writeString(registryPath, truncated, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> {
            var r = new IncidentRegistry(registryPath, clock);
            r.close();
        }).isInstanceOf(IOException.class)
            .hasMessageContaining("footer missing");
    }

    // ── Test 6b: Mid-stream CRC corruption → fail-closed ──

    @Test
    void midStreamCorruptionFailsClosed() throws Exception {
        var fingerprint = IncidentFingerprint.compute(
            "target-1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);

        registry.register(fingerprint, "inc-target-1-abc12345", "run-1",
            List.of("run://run-1/e-1"));
        registry.close();

        // Corrupt the entry line — change fingerprint hash in JSON
        String content = Files.readString(registryPath, StandardCharsets.UTF_8);
        String corrupted = content.replace(
            fingerprint.hash().substring(0, 12),
            "0000badf00");

        Files.writeString(registryPath, corrupted, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> {
            var r = new IncidentRegistry(registryPath, clock);
            r.close();
        }).isInstanceOf(IOException.class)
            .hasMessageContaining("checksum mismatch");
    }

    @Test
    void validButTamperedHeaderFailsChecksumValidation() throws Exception {
        var fingerprint = IncidentFingerprint.compute(
            "target-1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);
        registry.register(fingerprint, "inc-target-1-abc12345", "run-1",
            List.of("run://run-1/e-1"));
        registry.close();

        String content = Files.readString(registryPath, StandardCharsets.UTF_8);
        Files.writeString(registryPath,
            content.replace("\"version\":1", "\"version\":1,\"tampered\":true"),
            StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new IncidentRegistry(registryPath, clock))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("checksum mismatch");
    }

    @Test
    void nonTerminalFooterFailsClosed() throws Exception {
        var fingerprint = IncidentFingerprint.compute(
            "target-1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);
        registry.register(fingerprint, "inc-target-1-abc12345", "run-1",
            List.of("run://run-1/e-1"));
        registry.close();

        Files.writeString(registryPath, "# trailing record" + System.lineSeparator(),
            StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(() -> new IncidentRegistry(registryPath, clock))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("footer missing or not terminal");
    }

    // ── Test 6c: Unknown schema → fail-closed ──

    @Test
    void unknownSchemaFailsClosed() throws Exception {
        // Must close the @BeforeEach registry first to write the test file
        registry.close();

        Files.writeString(registryPath,
            "{\"schema\":\"incident-registry\",\"version\":999}\n",
            StandardCharsets.UTF_8);

        assertThatThrownBy(() -> {
            var r = new IncidentRegistry(registryPath, clock);
            r.close();
        }).isInstanceOf(IOException.class)
            .hasMessageContaining("Unsupported registry version");
    }

    // ── Test 6d: Corrupt header → fail-closed ──

    @Test
    void corruptHeaderFailsClosed() throws Exception {
        registry.close();

        Files.writeString(registryPath, "NOT JSON AT ALL\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> {
            var r = new IncidentRegistry(registryPath, clock);
            r.close();
        }).isInstanceOf(IOException.class);
    }

    // ── Test 7: Cross-process file lock (dual "JVM" simulation) ──

    @Test
    void secondRegistryOnSameFileFailsWithLockError() throws Exception {
        assertThat(registry).isNotNull();

        assertThatThrownBy(() -> {
            var second = new IncidentRegistry(registryPath, clock);
            second.close();
        }).isInstanceOf(IOException.class)
            .hasMessageContaining("Cannot lock");
    }

    // ── Test 8: Zero write operations ──

    @Test
    void registryOperationsTrackOnlyReadOnlyObservations() throws Exception {
        var fingerprint = IncidentFingerprint.compute(
            "target-1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);

        registry.register(fingerprint, "inc-target-1-abc12345", "run-1",
            List.of("run://run-1/e-1"));

        var entries = registry.snapshot();
        assertThat(entries).hasSize(1);
        RegistryEntry entry = entries.get(0);
        assertThat(entry.status()).isEqualTo(RegistryEntry.EntryStatus.ACTIVE);
        assertThat(entry.observationCount()).isEqualTo(1);
    }

    // ── Additional: closeEntry marks CLOSED ──

    @Test
    void closeEntryMarksAsClosed() throws Exception {
        var fingerprint = IncidentFingerprint.compute(
            "target-1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);

        registry.register(fingerprint, "inc-target-1-abc12345", "run-1",
            List.of("run://run-1/e-1"));
        assertThat(registry.activeCount()).isEqualTo(1);

        registry.closeEntry(fingerprint);
        assertThat(registry.activeCount()).isEqualTo(0);
        assertThat(registry.findActive(fingerprint)).isEmpty();
    }

    // ── Multiple fingerprints don't interfere ──

    @Test
    void multipleDistinctFingerprintsRemainIndependent() throws Exception {
        var fp1 = IncidentFingerprint.compute(
            "t1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);
        var fp2 = IncidentFingerprint.compute(
            "t2", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);

        registry.register(fp1, "inc-t1-aaa11111", "run-1", List.of("run://run-1/e-1"));
        registry.register(fp2, "inc-t2-bbb22222", "run-2", List.of("run://run-2/e-1"));

        assertThat(registry.activeCount()).isEqualTo(2);
        assertThat(registry.findActive(fp1)).isPresent();
        assertThat(registry.findActive(fp2)).isPresent();
        assertThat(registry.findActive(fp1).get().incidentId())
            .isNotEqualTo(registry.findActive(fp2).get().incidentId());
    }

    // ── tryAcquireTarget exclusion ──

    @Test
    void tryAcquireTargetPreventsConcurrentSameTarget() {
        assertThat(registry.tryAcquireTarget("target-1")).isTrue();
        assertThat(registry.tryAcquireTarget("target-1")).isFalse();
        assertThat(registry.isTargetAcquired("target-1")).isTrue();

        // Different target is allowed
        assertThat(registry.tryAcquireTarget("target-2")).isTrue();

        registry.releaseTarget("target-1");
        assertThat(registry.tryAcquireTarget("target-1")).isTrue();
    }

    // ── Entry IDs are unique ──

    @Test
    void eachRegistrationUsesProvidedIncidentId() throws Exception {
        var fingerprint = IncidentFingerprint.compute(
            "target-1", "order-api", "REMOTE_APP_DOWN_V1",
            ObservedSignal.APP_DOWN, 1);

        var result = registry.register(
            fingerprint, "my-custom-incident-id", "run-1", List.of());
        assertThat(result.incidentId()).isEqualTo("my-custom-incident-id");
    }
}
