package com.clawkit.ops.loop.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Generates reproducible, machine-readable evidence for the accelerated
 * 72-logical-hour Fixture A0 soak. This runner has no remote, Provider, or
 * repair dependency and cannot execute a write action.
 */
public final class FixtureAcceleratedSoakRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Instant LOGICAL_START = Instant.parse("2026-08-05T00:00:00Z");
    private static final int LOGICAL_HOURS = 72;
    private static final String REPORT_FILE = "accelerated-soak-report.json";
    private static final String FOOTER_PREFIX = "# SHA-256: ";

    private FixtureAcceleratedSoakRunner() { }

    public record Counts(
        long requested,
        long started,
        long completed,
        long skipped,
        long merged,
        long failed
    ) { }

    public record Report(
        String schema,
        int version,
        String mode,
        int logicalHours,
        int cadenceMinutes,
        Instant logicalStartedAt,
        Instant logicalCompletedAt,
        List<Integer> pausedHours,
        int restartHour,
        List<Integer> healthyHours,
        List<Integer> unknownHours,
        Counts counts,
        long timelineEvents,
        int registryEntries,
        int activeIncidents,
        int providerConsumed,
        int providerLimit,
        String snapshotId,
        String snapshotDirectory,
        boolean passed,
        List<String> verifiedInvariants
    ) {
        public Report {
            pausedHours = List.copyOf(pausedHours);
            healthyHours = List.copyOf(healthyHours);
            unknownHours = List.copyOf(unknownHours);
            verifiedInvariants = List.copyOf(verifiedInvariants);
        }
    }

    public record Result(
        Path runDirectory,
        Path reportPath,
        FixtureEvidenceSnapshot.Result snapshot,
        Report report
    ) { }

    /** Run the accelerated scenario in a new never-overwritten directory. */
    public static Result run(Path outputRoot) throws IOException {
        Objects.requireNonNull(outputRoot, "outputRoot");
        Path root = outputRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path runDirectory = root.resolve("ops-3a-soak-" + UUID.randomUUID()).normalize();
        if (!runDirectory.startsWith(root)) throw new IOException("Unsafe soak output directory");
        Files.createDirectory(runDirectory);

        MutableClock clock = new MutableClock(LOGICAL_START, ZoneOffset.UTC);
        FixtureObserveOnlyLoop loop = null;
        SteppableScheduler scheduler = null;
        AutomationStatus status;
        AutomationBudgetStatus budget;
        List<RegistryEntry> incidents;
        try {
            scheduler = new SteppableScheduler();
            loop = openLoop(runDirectory, clock, scheduler);
            loop.start();

            for (int hour = 0; hour < LOGICAL_HOURS; hour++) {
                if (hour == 20) loop.pause();
                if (hour == 24) loop.resume();
                if (hour == 30) {
                    loop.close();
                    loop = null;
                    scheduler = new SteppableScheduler();
                    loop = openLoop(runDirectory, clock, scheduler);
                    loop.start();
                }
                loop.setSignal(signalAt(hour));
                scheduler.tick();
                clock.advance(Duration.ofHours(1));
            }

            status = loop.status();
            budget = loop.budgetStatus();
            incidents = loop.incidents();
        } finally {
            if (loop != null) loop.close();
        }

        List<FixtureObservationTimelineReader.Entry> timeline =
            FixtureObservationTimelineReader.read(
                runDirectory.resolve("observation-timeline.jsonl"), 100);
        FixtureEvidenceSnapshot.Result snapshot =
            FixtureEvidenceSnapshot.create(runDirectory, clock);

        Counts counts = new Counts(status.requested(), status.started(), status.completed(),
            status.skipped(), status.merged(), status.failed());
        int activeIncidents = (int) incidents.stream()
            .filter(entry -> entry.status() == RegistryEntry.EntryStatus.ACTIVE).count();
        boolean passed = status.requested() == 68
            && status.started() == 68
            && status.completed() == 22
            && status.merged() == 46
            && status.skipped() == 0
            && status.failed() == 0
            && timeline.size() == 68
            && incidents.size() == 1
            && activeIncidents == 1
            && budget.providerConsumed() == 0
            && budget.providerLimit() == 0;

        Report report = new Report(
            "fixture-accelerated-soak-report", 1, "ACCELERATED_LOGICAL_TIME",
            LOGICAL_HOURS, 60, LOGICAL_START, clock.instant(),
            List.of(20, 21, 22, 23), 30,
            List.of(12, 36, 43), List.of(14, 38), counts, timeline.size(),
            incidents.size(), activeIncidents, budget.providerConsumed(), budget.providerLimit(),
            snapshot.snapshotId(), runDirectory.relativize(snapshot.directory()).toString()
                .replace('\\', '/'), passed,
            List.of(
                "fixture_target_only",
                "state_registry_timeline_verified",
                "restart_preserved_registry",
                "provider_budget_zero",
                "diagnosis_disabled",
                "no_remote_or_repair_capability"
            ));
        if (!passed) {
            throw new IOException("Accelerated Fixture soak did not satisfy its evidence contract: " + counts);
        }

        // Keep the optional report beside the four snapshot files so a browser file
        // picker can import all five in one operation. The manifest still binds only
        // the three core evidence files; this companion report has its own checksum
        // and cross-binds itself to snapshotId and timelineEvents.
        Path reportPath = snapshot.directory().resolve(REPORT_FILE);
        String payload = MAPPER.writeValueAsString(report);
        writeDurably(reportPath, payload + System.lineSeparator()
            + FOOTER_PREFIX + sha256(payload) + System.lineSeparator());
        return new Result(runDirectory, reportPath, snapshot, verifyReport(reportPath));
    }

    /**
     * Create one fresh APP_DOWN snapshot for a positive A3 counterfactual.
     *
     * <p>This is intentionally separate from the 72-logical-hour soak: its
     * sole purpose is to prove that fresh, dual evidence may be recorded as an
     * A3 candidate while still producing zero side effects. It has no report
     * and no remote, Provider, or repair capability.
     */
    public static FixtureEvidenceSnapshot.Result runFreshAppDown(Path outputRoot) throws IOException {
        Objects.requireNonNull(outputRoot, "outputRoot");
        Path root = outputRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path runDirectory = root.resolve("ops-3a-fresh-" + UUID.randomUUID()).normalize();
        if (!runDirectory.startsWith(root)) throw new IOException("Unsafe fresh evidence directory");
        Files.createDirectory(runDirectory);

        MutableClock clock = new MutableClock(LOGICAL_START, ZoneOffset.UTC);
        SteppableScheduler scheduler = new SteppableScheduler();
        FixtureObserveOnlyLoop loop = null;
        try {
            loop = openLoop(runDirectory, clock, scheduler);
            loop.start();
            loop.setSignal(ObservedSignal.APP_DOWN);
            scheduler.tick();
        } finally {
            if (loop != null) loop.close();
        }
        return FixtureEvidenceSnapshot.create(runDirectory, clock);
    }

    /** Read and verify a generated report without running the scenario again. */
    public static Report verifyReport(Path reportPath) throws IOException {
        Objects.requireNonNull(reportPath, "reportPath");
        Path path = reportPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || Files.size(path) < 1 || Files.size(path) > 1_048_576) {
            throw new IOException("Accelerated soak report is missing or unsafe");
        }
        String normalized = Files.readString(path, StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        if (lines.length != 3 || lines[0].isBlank() || !lines[2].isEmpty()
            || !lines[1].startsWith(FOOTER_PREFIX)) {
            throw new IOException("Accelerated soak report checksum footer is malformed");
        }
        String stored = lines[1].substring(FOOTER_PREFIX.length());
        if (!stored.matches("[0-9a-f]{64}") || !stored.equals(sha256(lines[0]))) {
            throw new IOException("Accelerated soak report checksum mismatch");
        }
        Report report;
        try {
            report = MAPPER.readValue(lines[0], Report.class);
        } catch (Exception e) {
            throw new IOException("Accelerated soak report payload is malformed", e);
        }
        Counts counts = report.counts();
        String snapshotId = report.snapshotId();
        if (!"fixture-accelerated-soak-report".equals(report.schema())
            || report.version() != 1
            || !"ACCELERATED_LOGICAL_TIME".equals(report.mode())
            || report.logicalHours() != LOGICAL_HOURS
            || report.cadenceMinutes() != 60
            || !LOGICAL_START.equals(report.logicalStartedAt())
            || !LOGICAL_START.plus(Duration.ofHours(LOGICAL_HOURS))
                .equals(report.logicalCompletedAt())
            || !List.of(20, 21, 22, 23).equals(report.pausedHours())
            || report.restartHour() != 30
            || !List.of(12, 36, 43).equals(report.healthyHours())
            || !List.of(14, 38).equals(report.unknownHours())
            || counts == null
            || counts.requested() != 68
            || counts.started() != 68
            || counts.completed() != 22
            || counts.skipped() != 0
            || counts.merged() != 46
            || counts.failed() != 0
            || report.timelineEvents() != 68
            || report.registryEntries() != 1
            || report.activeIncidents() != 1
            || !report.passed()
            || report.providerConsumed() != 0
            || report.providerLimit() != 0
            || snapshotId == null
            || !snapshotId.matches("fixture-snapshot-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            || !("snapshots/" + snapshotId).equals(report.snapshotDirectory())
            || report.timelineEvents() != counts.completed() + counts.merged()
            || !List.of(
                "fixture_target_only",
                "state_registry_timeline_verified",
                "restart_preserved_registry",
                "provider_budget_zero",
                "diagnosis_disabled",
                "no_remote_or_repair_capability"
            ).equals(report.verifiedInvariants())) {
            throw new IOException("Accelerated soak report contract is not satisfied");
        }
        return report;
    }

    private static FixtureObserveOnlyLoop openLoop(Path directory, Clock clock,
                                                    AutomationTaskScheduler scheduler)
        throws IOException {
        return new FixtureObserveOnlyLoop(new FixtureObserveOnlyLoop.Config(
            directory, "fixture-app-down", "order-api", Duration.ZERO, Duration.ofHours(1),
            new AutomationBudgetPolicy(1, Duration.ofHours(1), 100, 0)), clock, scheduler);
    }

    private static ObservedSignal signalAt(int hour) {
        if (hour == 12 || hour == 36 || hour == 43) return ObservedSignal.HEALTHY;
        if (hour == 14 || hour == 38) return ObservedSignal.UNKNOWN;
        return ObservedSignal.APP_DOWN;
    }

    private static void writeDurably(Path path, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(path,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override public ZoneId getZone() { return zone; }

        @Override public Clock withZone(ZoneId requestedZone) {
            return new MutableClock(instant, requestedZone);
        }

        @Override public Instant instant() { return instant; }

        private void advance(Duration duration) { instant = instant.plus(duration); }
    }

    private static final class SteppableScheduler implements AutomationTaskScheduler {
        private final List<Task> tasks = new ArrayList<>();
        private boolean closed;

        @Override
        public synchronized ScheduledHandle schedule(
            Runnable runnable, long initialDelay, long delay, TimeUnit unit
        ) {
            if (closed) throw new IllegalStateException("scheduler closed");
            Task task = new Task(runnable);
            tasks.add(task);
            return task;
        }

        private synchronized void tick() {
            for (Task task : List.copyOf(tasks)) {
                if (!task.cancelled) task.runnable.run();
            }
        }

        @Override public synchronized void close() {
            closed = true;
            tasks.clear();
        }

        private static final class Task implements ScheduledHandle {
            private final Runnable runnable;
            private boolean cancelled;

            private Task(Runnable runnable) { this.runnable = runnable; }

            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                boolean changed = !cancelled;
                cancelled = true;
                return changed;
            }

            @Override public boolean isCancelled() { return cancelled; }
        }
    }
}
