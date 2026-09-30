package com.clawkit.ops.loop.automation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent store for automation scheduler state.
 *
 * <h3>File format</h3>
 * Single JSON object with SHA-256 footer:
 * <pre>{@code
 * {"schema":"automation-state","version":1,"globalPaused":false,...}
 * # SHA-256: <hex>
 * }</pre>
 *
 * <h3>Safety</h3>
 * <ul>
 *   <li>Temp file + force(true) + atomic rename on every write.</li>
 *   <li>SHA-256 footer verified on load.</li>
 *   <li>Cross-JVM FileLock on a separate .lock file.</li>
 *   <li>Any corruption, unknown version, or lock failure → fail-closed.</li>
 * </ul>
 */
public final class AutomationStateStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AutomationStateStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());
    private static final String SCHEMA = "automation-state";
    static final int VERSION = 1;
    static final String FOOTER_PREFIX = "# SHA-256: ";

    private final Path path;
    private final Path lockPath;
    private final Clock clock;
    private final FileChannel lockChannel;
    private final FileLock fileLock;

    // ── In-memory state ──
    private boolean globalPaused;
    private AutomationBudgetPolicy budgetPolicy;
    private final Map<String, TargetState> targets = new ConcurrentHashMap<>();

    record TargetState(
        boolean paused,
        long requested, long started, long completed, long skipped, long merged, long failed,
        String lastRunId, String lastOutcome, String lastSkipReason,
        String inFlightRunId, Instant updatedAt,
        long budgetWindowEpoch, int discoveryConsumed, int providerConsumed
    ) {
        TargetState {
            if (requested < 0 || started < 0 || completed < 0 || skipped < 0
                || merged < 0 || failed < 0) {
                throw new IllegalArgumentException("automation counters must not be negative");
            }
            if (budgetWindowEpoch < 0 || discoveryConsumed < 0 || providerConsumed < 0) {
                throw new IllegalArgumentException("budget state must not be negative");
            }
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    enum ObservationAdmission {
        STARTED,
        SKIPPED_GLOBAL_PAUSED,
        SKIPPED_TARGET_PAUSED,
        SKIPPED_TARGET_BUSY,
        SKIPPED_DISCOVERY_BUDGET
    }

    public AutomationStateStore(Path path, Clock clock) throws IOException {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.lockPath = this.path.resolveSibling(this.path.getFileName() + ".lock");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.budgetPolicy = AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY;

        Files.createDirectories(this.path.getParent());

        this.lockChannel = FileChannel.open(lockPath,
            StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            this.fileLock = lockChannel.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            lockChannel.close();
            throw new IOException("Cannot lock state file: " + lockPath, e);
        }
        if (this.fileLock == null) {
            lockChannel.close();
            throw new IOException("Cannot lock state file: " + lockPath
                + " — another process may hold the lock");
        }

        try {
            load();
        } catch (Exception e) {
            close();
            if (e instanceof IOException ioe) throw ioe;
            throw new IOException("Failed to load state: " + e.getMessage(), e);
        }
    }

    // ── Accessors ────────────────────────────────────────────────

    public synchronized boolean isGlobalPaused() { return globalPaused; }

    public synchronized boolean isTargetPaused(String targetId) {
        TargetState ts = targets.get(targetId);
        return ts != null && ts.paused;
    }

    public synchronized AutomationStatus status(String targetId) {
        TargetState ts = targets.get(targetId);
        if (ts == null) return AutomationStatus.initial(targetId, clock.instant());
        return new AutomationStatus(targetId, ts.paused, ts.requested, ts.started,
            ts.completed, ts.skipped, ts.merged, ts.failed,
            ts.lastRunId, ts.lastOutcome, ts.lastSkipReason,
            ts.inFlightRunId, ts.updatedAt);
    }

    public synchronized AutomationBudgetPolicy budgetPolicy() { return budgetPolicy; }

    /** Report current-window consumption and remaining budget for one target. */
    public synchronized AutomationBudgetStatus budgetStatus(String targetId) {
        validateTargetId(targetId);
        TargetState ts = targets.get(targetId);
        long epoch = currentBudgetWindowEpoch();
        boolean sameWindow = ts != null && ts.budgetWindowEpoch == epoch;
        int discoveryConsumed = sameWindow ? ts.discoveryConsumed : 0;
        int providerConsumed = sameWindow ? ts.providerConsumed : 0;
        int discoveryLimit = budgetPolicy.maxDiscoveryRuns();
        int providerLimit = budgetPolicy.maxProviderCalls();
        Instant windowStart = Instant.ofEpochMilli(epoch);
        return new AutomationBudgetStatus(
            targetId, budgetPolicy.policyVersion(), windowStart,
            windowStart.plus(budgetPolicy.window()),
            discoveryLimit, discoveryConsumed,
            Math.max(0, discoveryLimit - discoveryConsumed),
            providerLimit, providerConsumed,
            Math.max(0, providerLimit - providerConsumed));
    }

    /** Expose targets for recovery/scheduling (read-only). */
    public synchronized Map<String, TargetState> targets() {
        return Map.copyOf(targets);
    }

    // ── Mutators ─────────────────────────────────────────────────

    public synchronized void setBudgetPolicy(AutomationBudgetPolicy policy) throws IOException {
        Objects.requireNonNull(policy, "policy");
        AutomationBudgetPolicy previous = this.budgetPolicy;
        this.budgetPolicy = policy;
        try {
            flush();
        } catch (IOException e) {
            this.budgetPolicy = previous;
            throw e;
        }
    }

    public synchronized void setGlobalPaused(boolean paused) throws IOException {
        boolean previous = this.globalPaused;
        this.globalPaused = paused;
        try {
            flush();
        } catch (IOException e) {
            this.globalPaused = previous;
            throw e;
        }
    }

    public synchronized void setTargetPaused(String targetId, boolean paused) throws IOException {
        validateTargetId(targetId);
        TargetState previous = targets.get(targetId);
        TargetState ts = previous == null ? initialTarget() : previous;
        persistTarget(targetId, previous, new TargetState(
            paused, ts.requested, ts.started, ts.completed, ts.skipped, ts.merged, ts.failed,
            ts.lastRunId, ts.lastOutcome, ts.lastSkipReason, ts.inFlightRunId,
            clock.instant(), ts.budgetWindowEpoch, ts.discoveryConsumed, ts.providerConsumed));
    }

    public synchronized TargetState ensureTarget(String targetId) {
        validateTargetId(targetId);
        return targets.computeIfAbsent(targetId,
            k -> initialTarget());
    }

    /** Persist target registration before its first scheduled trigger. */
    public synchronized void registerTarget(String targetId) throws IOException {
        validateTargetId(targetId);
        if (targets.containsKey(targetId)) return;
        persistTarget(targetId, null, initialTarget());
    }

    public synchronized void updateTarget(String targetId, TargetState ts) throws IOException {
        validateTargetId(targetId);
        Objects.requireNonNull(ts, "target state");
        persistTarget(targetId, targets.get(targetId), ts);
    }

    /**
     * Atomically checks pause/budget gates and, only when admitted, pre-commits
     * requested + started + discovery budget + in-flight state in one durable write.
     */
    public synchronized ObservationAdmission beginObservation(String targetId, String runId)
        throws IOException {
        validateTargetId(targetId);
        validateRunId(runId);
        TargetState previous = targets.get(targetId);
        TargetState ts = previous == null ? initialTarget() : previous;
        ObservationAdmission admission;
        TargetState updated;
        Instant now = clock.instant();

        if (globalPaused) {
            admission = ObservationAdmission.SKIPPED_GLOBAL_PAUSED;
            updated = skippedTrigger(ts, admission.name(), now);
        } else if (ts.paused) {
            admission = ObservationAdmission.SKIPPED_TARGET_PAUSED;
            updated = skippedTrigger(ts, admission.name(), now);
        } else if (ts.inFlightRunId != null) {
            admission = ObservationAdmission.SKIPPED_TARGET_BUSY;
            updated = skippedTrigger(ts, admission.name(), now);
        } else {
            long epoch = currentBudgetWindowEpoch();
            boolean sameWindow = ts.budgetWindowEpoch == epoch;
            int discoveryConsumed = sameWindow ? ts.discoveryConsumed : 0;
            int providerConsumed = sameWindow ? ts.providerConsumed : 0;
            if (budgetPolicy.maxDiscoveryRuns() == 0
                || discoveryConsumed >= budgetPolicy.maxDiscoveryRuns()) {
                admission = ObservationAdmission.SKIPPED_DISCOVERY_BUDGET;
                updated = new TargetState(
                    ts.paused, ts.requested + 1, ts.started, ts.completed,
                    ts.skipped + 1, ts.merged, ts.failed,
                    ts.lastRunId, ts.lastOutcome, admission.name(), ts.inFlightRunId,
                    now, epoch, discoveryConsumed, providerConsumed);
            } else {
                admission = ObservationAdmission.STARTED;
                updated = new TargetState(
                    ts.paused, ts.requested + 1, ts.started + 1, ts.completed,
                    ts.skipped, ts.merged, ts.failed,
                    runId, null, null, runId, now,
                    epoch, discoveryConsumed + 1, providerConsumed);
            }
        }
        persistTarget(targetId, previous, updated);
        return admission;
    }

    /** Record a trigger rejected by the process-local active guard. */
    public synchronized void recordBusyTrigger(String targetId) throws IOException {
        validateTargetId(targetId);
        TargetState previous = targets.get(targetId);
        TargetState ts = previous == null ? initialTarget() : previous;
        persistTarget(targetId, previous,
            skippedTrigger(ts, ObservationAdmission.SKIPPED_TARGET_BUSY.name(), clock.instant()));
    }

    /** Commit a terminal observation result and clear its in-flight marker atomically. */
    public synchronized void completeObservation(
        String targetId, String runId, String outcome, boolean merged) throws IOException {
        TargetState ts = requireInFlight(targetId, runId);
        TargetState updated = new TargetState(
            ts.paused, ts.requested, ts.started,
            ts.completed + (merged ? 0 : 1), ts.skipped,
            ts.merged + (merged ? 1 : 0), ts.failed,
            runId, requireText(outcome, "outcome"), null, null, clock.instant(),
            ts.budgetWindowEpoch, ts.discoveryConsumed, ts.providerConsumed);
        persistTarget(targetId, ts, updated);
    }

    /** Commit a failed observation and clear its in-flight marker atomically. */
    public synchronized void failObservation(String targetId, String runId) throws IOException {
        TargetState ts = requireInFlight(targetId, runId);
        TargetState updated = new TargetState(
            ts.paused, ts.requested, ts.started, ts.completed, ts.skipped, ts.merged,
            ts.failed + 1, runId, "FAILED", null, null, clock.instant(),
            ts.budgetWindowEpoch, ts.discoveryConsumed, ts.providerConsumed);
        persistTarget(targetId, ts, updated);
    }

    /** Commit a skipped in-flight observation and clear its marker atomically. */
    public synchronized void skipObservation(String targetId, String runId, String reason)
        throws IOException {
        TargetState ts = requireInFlight(targetId, runId);
        TargetState updated = new TargetState(
            ts.paused, ts.requested, ts.started, ts.completed, ts.skipped + 1,
            ts.merged, ts.failed, runId, null, requireText(reason, "reason"), null,
            clock.instant(), ts.budgetWindowEpoch, ts.discoveryConsumed, ts.providerConsumed);
        persistTarget(targetId, ts, updated);
    }

    /** Record why diagnosis was not performed without double-counting the observation. */
    public synchronized void recordDiagnosisSkip(String targetId, String runId, String reason)
        throws IOException {
        validateTargetId(targetId);
        validateRunId(runId);
        TargetState ts = Objects.requireNonNull(targets.get(targetId), "unknown targetId");
        TargetState updated = new TargetState(
            ts.paused, ts.requested, ts.started, ts.completed, ts.skipped, ts.merged,
            ts.failed, ts.lastRunId, ts.lastOutcome, requireText(reason, "reason"),
            ts.inFlightRunId, clock.instant(), ts.budgetWindowEpoch,
            ts.discoveryConsumed, ts.providerConsumed);
        persistTarget(targetId, ts, updated);
    }

    // ── Budget ────────────────────────────────────────────────────

    /** Determine the current epoch-aligned window. */
    public long currentBudgetWindowEpoch() {
        long windowMs = budgetPolicy.window().toMillis();
        return (clock.millis() / windowMs) * windowMs;
    }

    /**
     * Try to consume one discovery budget slot for a target.
     * @return true if budget was available and consumed
     */
    public synchronized boolean tryConsumeDiscovery(String targetId) throws IOException {
        if (budgetPolicy.maxDiscoveryRuns() == 0) return false;
        long epoch = currentBudgetWindowEpoch();
        TargetState previous = targets.get(targetId);
        TargetState ts = previous == null ? initialTarget() : previous;
        boolean sameWindow = ts.budgetWindowEpoch == epoch;
        int consumed = sameWindow ? ts.discoveryConsumed : 0;
        int providerConsumed = sameWindow ? ts.providerConsumed : 0;
        if (consumed >= budgetPolicy.maxDiscoveryRuns()) return false;
        TargetState updated = new TargetState(
            ts.paused, ts.requested, ts.started, ts.completed, ts.skipped, ts.merged, ts.failed,
            ts.lastRunId, ts.lastOutcome, ts.lastSkipReason, ts.inFlightRunId,
            clock.instant(), epoch, consumed + 1, providerConsumed);
        persistTarget(targetId, previous, updated);
        return true;
    }

    /**
     * Try to consume one provider budget slot for a target.
     * @return true if budget was available and consumed
     */
    public synchronized boolean tryConsumeProvider(String targetId) throws IOException {
        if (budgetPolicy.maxProviderCalls() == 0) return false;
        long epoch = currentBudgetWindowEpoch();
        TargetState previous = targets.get(targetId);
        TargetState ts = previous == null ? initialTarget() : previous;
        boolean sameWindow = ts.budgetWindowEpoch == epoch;
        int consumed = sameWindow ? ts.providerConsumed : 0;
        int discoveryConsumed = sameWindow ? ts.discoveryConsumed : 0;
        if (consumed >= budgetPolicy.maxProviderCalls()) return false;
        TargetState updated = new TargetState(
            ts.paused, ts.requested, ts.started, ts.completed, ts.skipped, ts.merged, ts.failed,
            ts.lastRunId, ts.lastOutcome, ts.lastSkipReason, ts.inFlightRunId,
            clock.instant(), epoch, discoveryConsumed, consumed + 1);
        persistTarget(targetId, previous, updated);
        return true;
    }

    // ── In-flight tracking ───────────────────────────────────────

    public synchronized void setInFlight(String targetId, String runId) throws IOException {
        validateTargetId(targetId);
        validateRunId(runId);
        TargetState previous = targets.get(targetId);
        TargetState ts = previous == null ? initialTarget() : previous;
        TargetState updated = new TargetState(
            ts.paused, ts.requested, ts.started, ts.completed, ts.skipped, ts.merged, ts.failed,
            runId, ts.lastOutcome, ts.lastSkipReason, runId, clock.instant(),
            ts.budgetWindowEpoch, ts.discoveryConsumed, ts.providerConsumed);
        persistTarget(targetId, previous, updated);
    }

    public synchronized void clearInFlight(String targetId) throws IOException {
        TargetState ts = targets.get(targetId);
        if (ts == null || ts.inFlightRunId == null) return;
        TargetState updated = new TargetState(
            ts.paused, ts.requested, ts.started, ts.completed, ts.skipped, ts.merged, ts.failed,
            ts.lastRunId, ts.lastOutcome, ts.lastSkipReason, null, clock.instant(),
            ts.budgetWindowEpoch, ts.discoveryConsumed, ts.providerConsumed);
        persistTarget(targetId, ts, updated);
    }

    // ── Recovery ──────────────────────────────────────────────────

    /** Recover abandoned in-flight runs after restart. */
    public synchronized int recoverAbandoned() throws IOException {
        Map<String, TargetState> previous = new LinkedHashMap<>(targets);
        int abandoned = 0;
        for (var entry : targets.entrySet()) {
            TargetState ts = entry.getValue();
            if (ts.inFlightRunId != null) {
                TargetState updated = new TargetState(
                    ts.paused, ts.requested, ts.started, ts.completed, ts.skipped, ts.merged,
                    ts.failed + 1,
                    ts.inFlightRunId, "ABANDONED_AFTER_RESTART", null, null,
                    clock.instant(),
                    ts.budgetWindowEpoch, ts.discoveryConsumed, ts.providerConsumed);
                targets.put(entry.getKey(), updated);
                abandoned++;
            }
        }
        if (abandoned > 0) {
            try {
                flush();
            } catch (IOException e) {
                targets.clear();
                targets.putAll(previous);
                throw e;
            }
        }
        return abandoned;
    }

    // ── Persistence ──────────────────────────────────────────────

    private void flush() throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("schema", SCHEMA);
        json.put("version", VERSION);
        json.put("globalPaused", globalPaused);
        json.put("budgetPolicy", Map.of(
            "policyVersion", budgetPolicy.policyVersion(),
            "windowMs", budgetPolicy.window().toMillis(),
            "maxDiscoveryRuns", budgetPolicy.maxDiscoveryRuns(),
            "maxProviderCalls", budgetPolicy.maxProviderCalls()));
        Map<String, Object> targetMap = new LinkedHashMap<>();
        for (var e : targets.entrySet()) {
            TargetState ts = e.getValue();
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("paused", ts.paused);
            tm.put("requested", ts.requested);
            tm.put("started", ts.started);
            tm.put("completed", ts.completed);
            tm.put("skipped", ts.skipped);
            tm.put("merged", ts.merged);
            tm.put("failed", ts.failed);
            if (ts.lastRunId != null) tm.put("lastRunId", ts.lastRunId);
            if (ts.lastOutcome != null) tm.put("lastOutcome", ts.lastOutcome);
            if (ts.lastSkipReason != null) tm.put("lastSkipReason", ts.lastSkipReason);
            if (ts.inFlightRunId != null) tm.put("inFlightRunId", ts.inFlightRunId);
            tm.put("updatedAt", ts.updatedAt.toString());
            tm.put("budgetWindowEpoch", ts.budgetWindowEpoch);
            tm.put("discoveryConsumed", ts.discoveryConsumed);
            tm.put("providerConsumed", ts.providerConsumed);
            targetMap.put(e.getKey(), tm);
        }
        json.put("targets", targetMap);
        String payload = MAPPER.writeValueAsString(json);
        String checksum = sha256(payload);
        String content = payload + System.lineSeparator()
            + FOOTER_PREFIX + checksum + System.lineSeparator();

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            try (FileChannel channel = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("Atomic state replacement is not supported for " + path, e);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private void load() throws IOException {
        if (!Files.exists(path)) {
            flush();
            return;
        }

        String content = Files.readString(path, StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
        String[] lines = content.split("\n", -1);
        if (lines.length != 3 || lines[0].isBlank() || !lines[2].isEmpty()
            || !lines[1].startsWith(FOOTER_PREFIX)) {
            throw new IOException("State file footer missing or malformed: " + path);
        }
        String payload = lines[0];
        String storedChecksum = lines[1].substring(FOOTER_PREFIX.length());

        if (!storedChecksum.matches("[0-9a-f]{64}")) {
            throw new IOException("State file footer checksum malformed: " + path);
        }

        String computed = sha256(payload);
        if (!computed.equals(storedChecksum)) {
            throw new IOException("State file checksum mismatch: stored="
                + storedChecksum.substring(0, Math.min(12, storedChecksum.length()))
                + " computed=" + computed.substring(0, Math.min(12, computed.length()))
                + " — file may be corrupt");
        }

        Map<String, Object> json;
        try {
            json = MAPPER.readValue(payload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IOException("State file payload is corrupt: " + path, e);
        }

        String schema = requiredString(json, "schema");
        int version = requiredInt(json, "version");
        if (!SCHEMA.equals(schema)) {
            throw new IOException("Unknown state schema: " + schema);
        }
        if (version != VERSION) {
            throw new IOException("Unsupported state version: " + version);
        }

        try {
            globalPaused = requiredBoolean(json, "globalPaused");
            Map<String, Object> bp = requiredMap(json, "budgetPolicy");
            budgetPolicy = new AutomationBudgetPolicy(
                requiredInt(bp, "policyVersion"),
                Duration.ofMillis(requiredLong(bp, "windowMs")),
                requiredInt(bp, "maxDiscoveryRuns"),
                requiredInt(bp, "maxProviderCalls"));

            Map<String, Object> loadedTargets = requiredMap(json, "targets");
            Map<String, TargetState> parsedTargets = new LinkedHashMap<>();
            for (var e : loadedTargets.entrySet()) {
                validateTargetId(e.getKey());
                Map<String, Object> tm = asMap(e.getValue(), "target " + e.getKey());
                parsedTargets.put(e.getKey(), new TargetState(
                    requiredBoolean(tm, "paused"),
                    requiredLong(tm, "requested"),
                    requiredLong(tm, "started"),
                    requiredLong(tm, "completed"),
                    requiredLong(tm, "skipped"),
                    requiredLong(tm, "merged"),
                    requiredLong(tm, "failed"),
                    optionalString(tm, "lastRunId"),
                    optionalString(tm, "lastOutcome"),
                    optionalString(tm, "lastSkipReason"),
                    optionalString(tm, "inFlightRunId"),
                    Instant.parse(requiredString(tm, "updatedAt")),
                    requiredLong(tm, "budgetWindowEpoch"),
                    requiredInt(tm, "discoveryConsumed"),
                    requiredInt(tm, "providerConsumed")));
            }
            targets.clear();
            targets.putAll(parsedTargets);
        } catch (IllegalArgumentException | ClassCastException e) {
            throw new IOException("State file payload fields are invalid: " + path, e);
        }
    }

    private TargetState initialTarget() {
        return new TargetState(false, 0, 0, 0, 0, 0, 0,
            null, null, null, null, clock.instant(), 0, 0, 0);
    }

    private static TargetState skippedTrigger(TargetState ts, String reason, Instant now) {
        return new TargetState(
            ts.paused, ts.requested + 1, ts.started, ts.completed, ts.skipped + 1,
            ts.merged, ts.failed, ts.lastRunId, ts.lastOutcome, reason,
            ts.inFlightRunId, now, ts.budgetWindowEpoch,
            ts.discoveryConsumed, ts.providerConsumed);
    }

    private TargetState requireInFlight(String targetId, String runId) {
        validateTargetId(targetId);
        validateRunId(runId);
        TargetState ts = targets.get(targetId);
        if (ts == null || !runId.equals(ts.inFlightRunId)) {
            throw new IllegalStateException("run is not in-flight for target " + targetId);
        }
        return ts;
    }

    private void persistTarget(String targetId, TargetState previous, TargetState updated)
        throws IOException {
        targets.put(targetId, updated);
        try {
            flush();
        } catch (IOException e) {
            if (previous == null) targets.remove(targetId);
            else targets.put(targetId, previous);
            throw e;
        }
    }

    private static void validateTargetId(String targetId) {
        requireText(targetId, "targetId");
    }

    private static void validateRunId(String runId) {
        requireText(runId, "runId");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Map<String, Object> requiredMap(Map<String, Object> map, String key) {
        if (!map.containsKey(key)) throw new IllegalArgumentException("missing field: " + key);
        return asMap(map.get(key), key);
    }

    private static Map<String, Object> asMap(Object value, String name) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(name + " contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static boolean requiredBoolean(Map<String, Object> map, String key) {
        Object value = requiredValue(map, key);
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException(key + " must be boolean");
        }
        return result;
    }

    private static int requiredInt(Map<String, Object> map, String key) {
        long value = requiredLong(map, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " is outside integer range");
        }
        return (int) value;
    }

    private static long requiredLong(Map<String, Object> map, String key) {
        Object value = requiredValue(map, key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        long result = number.longValue();
        if (number.doubleValue() != (double) result) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return result;
    }

    private static String requiredString(Map<String, Object> map, String key) {
        Object value = requiredValue(map, key);
        if (!(value instanceof String result) || result.isBlank()) {
            throw new IllegalArgumentException(key + " must be a non-blank string");
        }
        return result;
    }

    private static String optionalString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (!(value instanceof String result) || result.isBlank()) {
            throw new IllegalArgumentException(key + " must be a non-blank string when present");
        }
        return result;
    }

    private static Object requiredValue(Map<String, Object> map, String key) {
        if (!map.containsKey(key) || map.get(key) == null) {
            throw new IllegalArgumentException("missing field: " + key);
        }
        return map.get(key);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public void close() {
        try { if (fileLock != null && fileLock.isValid()) fileLock.release(); }
        catch (IOException e) { log.warn("Failed to release state lock: {}", e.getMessage()); }
        try { if (lockChannel != null && lockChannel.isOpen()) lockChannel.close(); }
        catch (IOException e) { log.warn("Failed to close state channel: {}", e.getMessage()); }
    }
}
