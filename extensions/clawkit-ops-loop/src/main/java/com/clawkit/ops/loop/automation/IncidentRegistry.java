package com.clawkit.ops.loop.automation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent, versioned, CRC-protected, file-locked Incident Registry.
 *
 * <h3>File format</h3>
 * <pre>{@code
 * {"schema":"incident-registry","version":1}
 * {entry-json}
 * ...
 * # SHA-256: <hex>
 * }</pre>
 *
 * <h3>Safety guarantees</h3>
 * <ul>
 *   <li>Exclusive cross-JVM file lock held for the registry lifetime.</li>
 *   <li>SHA-256 footer verified on every read — mid-stream corruption
 *       or tail truncation fails closed.</li>
 *   <li>Unknown schema version fails closed.</li>
 *   <li>{@code force(true)} on every write.</li>
 *   <li>Cannot-lock fails closed.</li>
 * </ul>
 */
public final class IncidentRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IncidentRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());
    private static final String SCHEMA = "incident-registry";
    private static final int VERSION = 1;
    private static final String FOOTER_PREFIX = "# SHA-256: ";

    private final Path path;
    private final Clock clock;
    private final IncidentCooldownPolicy cooldownPolicy;
    private final FileChannel channel;
    private final FileLock fileLock;
    private final Map<String, RegistryEntry> entries = new LinkedHashMap<>();
    private final Set<String> activeTargets = ConcurrentHashMap.newKeySet();

    public IncidentRegistry(Path path, Clock clock) throws IOException {
        this(path, clock, IncidentCooldownPolicy.DISABLED);
    }

    public IncidentRegistry(Path path, Clock clock, IncidentCooldownPolicy cooldownPolicy)
        throws IOException {
        this.path = path.toAbsolutePath().normalize();
        this.clock = clock;
        this.cooldownPolicy = Objects.requireNonNull(cooldownPolicy, "cooldownPolicy");

        Files.createDirectories(this.path.getParent());

        this.channel = FileChannel.open(
            this.path,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE);

        try {
            this.fileLock = channel.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            channel.close();
            throw new IOException("Cannot lock registry file: " + this.path
                + " — lock overlaps with an existing lock in this JVM", e);
        }
        if (this.fileLock == null) {
            channel.close();
            throw new IOException("Cannot lock registry file: " + this.path
                + " — another process may hold the lock");
        }

        try {
            load();
        } catch (Exception e) {
            close();
            if (e instanceof IOException ioe) throw ioe;
            throw new IOException("Failed to load registry: " + e.getMessage(), e);
        }

        log.debug("Registry opened: {} ({} entries)", this.path.getFileName(), entries.size());
    }

    // ── Target exclusion ──────────────────────────────────────────

    public boolean tryAcquireTarget(String targetId) {
        return activeTargets.add(targetId);
    }

    public void releaseTarget(String targetId) {
        activeTargets.remove(targetId);
    }

    public boolean isTargetAcquired(String targetId) {
        return activeTargets.contains(targetId);
    }

    // ── Registration ──────────────────────────────────────────────

    public synchronized RegistrationResult register(
        IncidentFingerprint fingerprint,
        String incidentId,
        String runId,
        List<String> evidenceRefs
    ) throws IOException {
        String fp = fingerprint.hash();
        Instant now = clock.instant();

        RegistryEntry existing = entries.get(fp);
        if (existing != null && existing.status() == RegistryEntry.EntryStatus.ACTIVE) {
            RegistryEntry updated = existing.withObservation(now, runId, evidenceRefs);
            entries.put(fp, updated);
            flush();
            log.debug("Registry merge: fp={} incident={} count={}",
                fp.substring(0, 12), existing.incidentId(), updated.observationCount());
            return RegistrationResult.merged(existing.incidentId(), updated.observationCount());
        }

        if (existing != null && existing.status() == RegistryEntry.EntryStatus.CLOSED
            && isCoolingDown(existing, now)) {
            log.debug("Registry cooldown skip: fp={} incident={} until={}",
                fp.substring(0, 12), existing.incidentId(),
                existing.lastObservedAt().plus(cooldownPolicy.window()));
            return RegistrationResult.cooldownSkipped(existing.incidentId());
        }

        RegistryEntry entry = RegistryEntry.create(fingerprint, incidentId, now, runId, evidenceRefs);
        entries.put(fp, entry);
        flush();
        log.debug("Registry create: fp={} incident={}", fp.substring(0, 12), incidentId);
        return RegistrationResult.created(incidentId);
    }

    public synchronized Optional<RegistryEntry> findActive(IncidentFingerprint fingerprint) {
        RegistryEntry entry = entries.get(fingerprint.hash());
        if (entry != null && entry.status() == RegistryEntry.EntryStatus.ACTIVE) {
            return Optional.of(entry);
        }
        return Optional.empty();
    }

    public synchronized void closeEntry(IncidentFingerprint fingerprint) throws IOException {
        String fp = fingerprint.hash();
        RegistryEntry existing = entries.get(fp);
        if (existing == null || existing.status() != RegistryEntry.EntryStatus.ACTIVE) {
            throw new IllegalStateException(
                "No ACTIVE entry for fingerprint: " + fp.substring(0, 12));
        }
        entries.put(fp, existing.closed(clock.instant()));
        flush();
        log.debug("Registry close: fp={} incident={}", fp.substring(0, 12),
            existing.incidentId());
    }

    /**
     * Close the matching ACTIVE entry if it exists.
     *
     * @return {@code true} only when an ACTIVE incident was durably closed
     */
    public synchronized boolean closeIfActive(IncidentFingerprint fingerprint) throws IOException {
        String fp = fingerprint.hash();
        RegistryEntry existing = entries.get(fp);
        if (existing == null || existing.status() != RegistryEntry.EntryStatus.ACTIVE) {
            return false;
        }
        entries.put(fp, existing.closed(clock.instant()));
        flush();
        log.info("Registry recovery close: fp={} incident={}", fp.substring(0, 12),
            existing.incidentId());
        return true;
    }

    public synchronized List<RegistryEntry> snapshot() {
        return List.copyOf(entries.values());
    }

    public synchronized int activeCount() {
        return (int) entries.values().stream()
            .filter(e -> e.status() == RegistryEntry.EntryStatus.ACTIVE).count();
    }

    private boolean isCoolingDown(RegistryEntry entry, Instant now) {
        Duration window = cooldownPolicy.window();
        return !window.isZero() && now.isBefore(entry.lastObservedAt().plus(window));
    }

    // ── Persistence ───────────────────────────────────────────────

    /** Rewrite the complete, checksummed registry payload and force it to disk. */
    private void flush() throws IOException {
        channel.truncate(0);
        channel.position(0);

        List<String> payloadLines = new ArrayList<>();
        payloadLines.add(MAPPER.writeValueAsString(Map.of("schema", SCHEMA, "version", VERSION)));
        for (RegistryEntry entry : entries.values()) {
            payloadLines.add(MAPPER.writeValueAsString(toJson(entry)));
        }
        for (String line : payloadLines) {
            channel.write(StandardCharsets.UTF_8.encode(line + System.lineSeparator()));
        }

        // Footer
        String footer = FOOTER_PREFIX + computeChecksum(payloadLines) + System.lineSeparator();
        channel.write(StandardCharsets.UTF_8.encode(footer));

        channel.force(true);
    }

    private void load() throws IOException {
        if (channel.size() == 0) {
            // New file — write header + empty footer
            flush();
            return;
        }

        // Read all bytes from channel
        channel.position(0);
        byte[] bytes = new byte[(int) channel.size()];
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        while (buf.hasRemaining()) {
            channel.read(buf);
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        List<String> lines = content.lines().toList();

        if (lines.isEmpty() || lines.stream().anyMatch(String::isBlank)) {
            throw new IOException("Registry file is empty (no header): " + path);
        }

        // Parse header
        Map<String, Object> header;
        try {
            header = MAPPER.readValue(lines.get(0),
                new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IOException("Registry header is corrupt: " + path, e);
        }

        String schema = String.valueOf(header.get("schema"));
        int version = ((Number) header.getOrDefault("version", 0)).intValue();

        if (!SCHEMA.equals(schema)) {
            throw new IOException("Unknown registry schema: " + schema
                + " (expected " + SCHEMA + ")");
        }
        if (version != VERSION) {
            throw new IOException("Unsupported registry version: " + version
                + " (expected " + VERSION + ")");
        }

        if (lines.size() < 2) {
            throw new IOException("Registry footer missing or not terminal: " + path);
        }
        String footer = lines.get(lines.size() - 1);
        if (!footer.startsWith(FOOTER_PREFIX)) {
            throw new IOException("Registry footer missing or not terminal: " + path);
        }
        String storedChecksum = footer.substring(FOOTER_PREFIX.length()).trim();
        if (!storedChecksum.matches("[0-9a-f]{64}")) {
            throw new IOException("Registry footer checksum is invalid: " + path);
        }
        List<String> payloadLines = lines.subList(0, lines.size() - 1);
        if (payloadLines.stream().anyMatch(line -> line.startsWith(FOOTER_PREFIX))) {
            throw new IOException("Registry contains a non-terminal footer: " + path);
        }

        // Parse entries
        entries.clear();
        for (String line : payloadLines.subList(1, payloadLines.size())) {
            Map<String, Object> json;
            try {
                json = MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                throw new IOException(
                    "Registry entry line is corrupt (mid-stream corruption): " + path, e);
            }
            RegistryEntry entry = fromJson(json);
            entries.put(entry.fingerprint(), entry);
        }

        // Verify checksum
        String computed = computeChecksum(payloadLines);
        if (!computed.equals(storedChecksum)) {
            throw new IOException("Registry checksum mismatch: stored="
                + storedChecksum.substring(0, Math.min(12, storedChecksum.length()))
                + "... computed="
                + computed.substring(0, Math.min(12, computed.length()))
                + "... — file may be corrupt");
        }

        log.debug("Registry loaded: {} entries from {}", entries.size(), path.getFileName());
    }

    private static String computeChecksum(List<String> payloadLines) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String line : payloadLines) {
                md.update(line.getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ── JSON helpers ──────────────────────────────────────────────

    private static Map<String, Object> toJson(RegistryEntry entry) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("fingerprint", entry.fingerprint());
        json.put("incidentId", entry.incidentId());
        json.put("status", entry.status().name());
        json.put("firstObservedAt", entry.firstObservedAt().toString());
        json.put("lastObservedAt", entry.lastObservedAt().toString());
        json.put("observationCount", entry.observationCount());
        json.put("lastRunId", entry.lastRunId());
        json.put("lastEvidenceRefs", entry.lastEvidenceRefs());
        return json;
    }

    private static RegistryEntry fromJson(Map<String, Object> json) {
        @SuppressWarnings("unchecked")
        List<String> refs = (List<String>) json.getOrDefault("lastEvidenceRefs", List.of());
        return new RegistryEntry(
            (String) json.get("fingerprint"),
            (String) json.get("incidentId"),
            RegistryEntry.EntryStatus.valueOf((String) json.get("status")),
            Instant.parse((String) json.get("firstObservedAt")),
            Instant.parse((String) json.get("lastObservedAt")),
            ((Number) json.get("observationCount")).longValue(),
            (String) json.get("lastRunId"),
            refs);
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    @Override
    public void close() {
        try {
            if (fileLock != null && fileLock.isValid()) {
                fileLock.release();
            }
        } catch (IOException e) {
            log.warn("Failed to release file lock: {}", e.getMessage());
        }
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (IOException e) {
            log.warn("Failed to close registry channel: {}", e.getMessage());
        }
    }

    // ── Registration result ───────────────────────────────────────

    public sealed interface RegistrationResult {
        String incidentId();
        boolean isNewIncident();

        default boolean isCooldownSkipped() { return false; }

        record Created(String incidentId) implements RegistrationResult {
            @Override public boolean isNewIncident() { return true; }
        }

        record Merged(String incidentId, long totalObservations) implements RegistrationResult {
            @Override public boolean isNewIncident() { return false; }
        }

        record CooldownSkipped(String incidentId) implements RegistrationResult {
            @Override public boolean isNewIncident() { return false; }
            @Override public boolean isCooldownSkipped() { return true; }
        }

        static Created created(String incidentId) {
            return new Created(incidentId);
        }

        static Merged merged(String incidentId, long totalObservations) {
            return new Merged(incidentId, totalObservations);
        }

        static CooldownSkipped cooldownSkipped(String incidentId) {
            return new CooldownSkipped(incidentId);
        }
    }
}
