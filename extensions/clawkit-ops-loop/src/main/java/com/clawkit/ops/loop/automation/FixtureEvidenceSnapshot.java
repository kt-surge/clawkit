package com.clawkit.ops.loop.automation;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Creates a self-contained, immutable evidence bundle for the Fixture A0 loop.
 *
 * <p>The bundle binds the exact bytes of state, registry, and timeline files in
 * one checksummed manifest. It is intentionally local-only and does not open a
 * remote session, load a Provider, or expose a repair capability.
 */
public final class FixtureEvidenceSnapshot {

    public static final String MANIFEST_FILE = "fixture-evidence-manifest.json";
    private static final String ACCELERATED_SOAK_REPORT_FILE = "accelerated-soak-report.json";
    private static final String STATE_FILE = "automation-state.json";
    private static final String REGISTRY_FILE = "incident-registry.jsonl";
    private static final String TIMELINE_FILE = "observation-timeline.jsonl";
    private static final List<String> EVIDENCE_FILES = List.of(
        STATE_FILE, REGISTRY_FILE, TIMELINE_FILE);
    private static final Set<String> BUNDLE_FILES = Set.of(
        STATE_FILE, REGISTRY_FILE, TIMELINE_FILE, MANIFEST_FILE);
    private static final Set<String> BUNDLE_WITH_SOAK_REPORT_FILES = Set.of(
        STATE_FILE, REGISTRY_FILE, TIMELINE_FILE, MANIFEST_FILE,
        ACCELERATED_SOAK_REPORT_FILE);
    private static final Set<String> MANIFEST_FIELDS = Set.of(
        "schema", "version", "snapshotId", "createdAt", "targetId", "timelineEvents", "files");
    private static final Set<String> DIGEST_FIELDS = Set.of("bytes", "sha256");
    private static final String SCHEMA = "fixture-evidence-snapshot";
    private static final int VERSION = 1;
    private static final String FOOTER_PREFIX = "# SHA-256: ";
    private static final long MAX_FILE_BYTES = 1_048_576;
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private FixtureEvidenceSnapshot() { }

    public record FileDigest(long bytes, String sha256) {
        public FileDigest {
            if (bytes < 1 || bytes > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("evidence file size is outside the supported range");
            }
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid evidence SHA-256");
            }
        }
    }

    public record Result(
        String snapshotId,
        Instant createdAt,
        String targetId,
        long timelineEvents,
        Path directory,
        Map<String, FileDigest> files
    ) {
        public Result {
            requireText(snapshotId, "snapshotId");
            Objects.requireNonNull(createdAt, "createdAt");
            requireText(targetId, "targetId");
            if (timelineEvents < 1) throw new IllegalArgumentException("timelineEvents must be positive");
            directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
            files = Map.copyOf(Objects.requireNonNull(files, "files"));
        }
    }

    /** Create a new never-overwritten bundle below {@code stateDirectory/snapshots}. */
    public static Result create(Path stateDirectory, Clock clock) throws IOException {
        Objects.requireNonNull(stateDirectory, "stateDirectory");
        Objects.requireNonNull(clock, "clock");
        Path source = stateDirectory.toAbsolutePath().normalize();
        EvidenceSummary sourceSummary = validateEvidenceFiles(source);

        String snapshotId = "fixture-snapshot-" + UUID.randomUUID();
        Path snapshots = source.resolve("snapshots").normalize();
        Path directory = snapshots.resolve(snapshotId).normalize();
        if (!directory.startsWith(snapshots)) throw new IOException("Unsafe snapshot directory");

        Files.createDirectories(snapshots);
        Files.createDirectory(directory);
        boolean complete = false;
        try {
            for (String fileName : EVIDENCE_FILES) {
                Files.copy(source.resolve(fileName), directory.resolve(fileName),
                    StandardCopyOption.COPY_ATTRIBUTES);
            }

            EvidenceSummary copiedSummary = validateEvidenceFiles(directory);
            if (!sourceSummary.equals(copiedSummary)) {
                throw new IOException("Fixture evidence changed while snapshot was being created");
            }

            Map<String, FileDigest> digests = digestEvidenceFiles(directory);
            Instant createdAt = clock.instant();
            String payload = manifestPayload(snapshotId, createdAt, copiedSummary, digests);
            writeDurably(directory.resolve(MANIFEST_FILE), payload + System.lineSeparator()
                + FOOTER_PREFIX + sha256(payload.getBytes(StandardCharsets.UTF_8))
                + System.lineSeparator());

            Result verified = verify(directory);
            complete = true;
            return verified;
        } finally {
            if (!complete) cleanupIncomplete(directory);
        }
    }

    /** Verify the manifest, every bound file byte, and the Fixture A0 semantic contract. */
    public static Result verify(Path snapshotDirectory) throws IOException {
        Objects.requireNonNull(snapshotDirectory, "snapshotDirectory");
        Path directory = snapshotDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Fixture snapshot is not a regular directory: " + directory);
        }

        Set<String> actualFiles = new LinkedHashSet<>();
        try (var children = Files.list(directory)) {
            children.forEach(path -> actualFiles.add(path.getFileName().toString()));
        }
        boolean hasSoakReport = actualFiles.equals(BUNDLE_WITH_SOAK_REPORT_FILES);
        if (!actualFiles.equals(BUNDLE_FILES) && !hasSoakReport) {
            throw new IOException("Fixture snapshot must contain the four bundle files and only the optional accelerated soak report");
        }

        JsonNode manifest = readManifest(directory.resolve(MANIFEST_FILE));
        if (!fieldNames(manifest).equals(MANIFEST_FIELDS)) {
            throw new IOException("Fixture snapshot manifest contains unknown or missing fields");
        }
        String snapshotId = requiredText(manifest, "snapshotId");
        if (!snapshotId.matches("fixture-snapshot-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IOException("Fixture snapshot id is invalid");
        }
        Instant createdAt;
        try {
            createdAt = Instant.parse(requiredText(manifest, "createdAt"));
        } catch (RuntimeException e) {
            throw new IOException("Fixture snapshot createdAt is invalid", e);
        }
        String targetId = requiredText(manifest, "targetId");
        long timelineEvents = requiredLong(manifest, "timelineEvents");
        if (timelineEvents < 1) throw new IOException("Fixture snapshot timelineEvents must be positive");

        JsonNode fileNodes = manifest.get("files");
        if (fileNodes == null || !fileNodes.isObject()
            || !fieldNames(fileNodes).equals(new LinkedHashSet<>(EVIDENCE_FILES))) {
            throw new IOException("Fixture snapshot manifest file set is invalid");
        }

        Map<String, FileDigest> expected = new LinkedHashMap<>();
        for (String fileName : EVIDENCE_FILES) {
            JsonNode node = fileNodes.get(fileName);
            if (node == null || !node.isObject() || !fieldNames(node).equals(DIGEST_FIELDS)) {
                throw new IOException("Fixture snapshot manifest digest shape is invalid for " + fileName);
            }
            try {
                expected.put(fileName, new FileDigest(
                    requiredLong(node, "bytes"), requiredText(node, "sha256")));
            } catch (IllegalArgumentException e) {
                throw new IOException("Fixture snapshot manifest digest is invalid for " + fileName, e);
            }
        }

        Map<String, FileDigest> actual = digestEvidenceFiles(directory);
        for (String fileName : EVIDENCE_FILES) {
            if (!expected.get(fileName).equals(actual.get(fileName))) {
                throw new IOException("Fixture snapshot hash mismatch for " + fileName);
            }
        }

        EvidenceSummary summary = validateEvidenceFiles(directory);
        if (!summary.targetId().equals(targetId)) {
            throw new IOException("Fixture snapshot target does not match bound evidence");
        }
        if (summary.timelineEvents() != timelineEvents) {
            throw new IOException("Fixture snapshot event count does not match bound evidence");
        }
        if (hasSoakReport) {
            FixtureAcceleratedSoakRunner.Report report =
                FixtureAcceleratedSoakRunner.verifyReport(
                    directory.resolve(ACCELERATED_SOAK_REPORT_FILE));
            if (!snapshotId.equals(report.snapshotId())
                || timelineEvents != report.timelineEvents()) {
                throw new IOException("Accelerated soak report does not bind this Fixture snapshot");
            }
        }
        return new Result(snapshotId, createdAt, targetId, timelineEvents, directory, expected);
    }

    private static EvidenceSummary validateEvidenceFiles(Path directory) throws IOException {
        Path statePath = requireEvidenceFile(directory, STATE_FILE);
        Path registryPath = requireEvidenceFile(directory, REGISTRY_FILE);
        Path timelinePath = requireEvidenceFile(directory, TIMELINE_FILE);

        JsonNode state = validateState(statePath);
        validateRegistry(registryPath);
        List<FixtureObservationTimelineReader.Entry> entries =
            FixtureObservationTimelineReader.read(timelinePath, 100);
        if (entries.isEmpty()) throw new IOException("Fixture timeline has no replayable events");
        if (entries.stream().anyMatch(entry -> entry.evidenceFacts().size()
            != entry.evidenceRefs().size())) {
            throw new IOException("Fixture timeline is missing the required allow-listed evidence facts");
        }

        JsonNode targets = state.get("targets");
        if (targets == null || !targets.isObject() || targets.size() != 1) {
            throw new IOException("Fixture state must contain exactly one target");
        }
        String targetId = targets.fieldNames().next();
        if (!targetId.matches("fixture-[a-z0-9][a-z0-9_-]{0,62}")) {
            throw new IOException("Fixture state contains an unsafe target");
        }
        if (entries.stream().anyMatch(entry -> !targetId.equals(entry.targetId()))) {
            throw new IOException("Fixture timeline target does not match state");
        }

        JsonNode target = targets.get(targetId);
        long expectedEvents = requiredNonNegativeLong(target, "completed")
            + requiredNonNegativeLong(target, "merged");
        long eventCount;
        try (var lines = Files.lines(timelinePath, StandardCharsets.UTF_8)) {
            eventCount = lines.filter(line -> !line.isBlank()).count();
        }
        if (eventCount != expectedEvents) {
            throw new IOException("Fixture timeline count does not match completed + merged state");
        }

        JsonNode budget = state.get("budgetPolicy");
        if (budget == null || !budget.isObject()
            || requiredNonNegativeLong(budget, "maxProviderCalls") != 0
            || requiredNonNegativeLong(target, "providerConsumed") != 0) {
            throw new IOException("Fixture snapshot must preserve Provider budget 0/0");
        }
        return new EvidenceSummary(targetId, eventCount);
    }

    private static JsonNode validateState(Path path) throws IOException {
        String normalized = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        if (lines.length != 3 || lines[0].isBlank() || !lines[2].isEmpty()
            || !lines[1].startsWith(FOOTER_PREFIX)) {
            throw new IOException("Fixture state checksum footer is malformed");
        }
        String stored = lines[1].substring(FOOTER_PREFIX.length());
        if (!stored.matches("[0-9a-f]{64}")
            || !stored.equals(sha256(lines[0].getBytes(StandardCharsets.UTF_8)))) {
            throw new IOException("Fixture state checksum mismatch");
        }
        JsonNode root = parseObject(lines[0], "Fixture state");
        if (!"automation-state".equals(requiredText(root, "schema"))
            || requiredLong(root, "version") != 1) {
            throw new IOException("Fixture state schema is unsupported");
        }
        return root;
    }

    private static void validateRegistry(Path path) throws IOException {
        String normalized = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String[] split = normalized.split("\n", -1);
        if (split.length < 3 || !split[split.length - 1].isEmpty()) {
            throw new IOException("Fixture registry checksum footer is malformed");
        }
        List<String> lines = new ArrayList<>(List.of(split));
        lines.removeLast();
        String footer = lines.removeLast();
        if (!footer.startsWith(FOOTER_PREFIX)) {
            throw new IOException("Fixture registry checksum footer is malformed");
        }
        if (lines.isEmpty() || lines.stream().anyMatch(String::isBlank)) {
            throw new IOException("Fixture registry payload is malformed");
        }
        String stored = footer.substring(FOOTER_PREFIX.length());
        MessageDigest digest = newDigest();
        for (String line : lines) {
            digest.update(line.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        if (!stored.matches("[0-9a-f]{64}")
            || !stored.equals(HexFormat.of().formatHex(digest.digest()))) {
            throw new IOException("Fixture registry checksum mismatch");
        }
        JsonNode header = parseObject(lines.getFirst(), "Fixture registry header");
        if (!"incident-registry".equals(requiredText(header, "schema"))
            || requiredLong(header, "version") != 1) {
            throw new IOException("Fixture registry schema is unsupported");
        }
        for (int index = 1; index < lines.size(); index++) {
            parseObject(lines.get(index), "Fixture registry entry " + index);
        }
    }

    private static JsonNode readManifest(Path path) throws IOException {
        requireRegularFile(path, MANIFEST_FILE);
        String normalized = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        if (lines.length != 3 || lines[0].isBlank() || !lines[2].isEmpty()
            || !lines[1].startsWith(FOOTER_PREFIX)) {
            throw new IOException("Fixture snapshot manifest checksum footer is malformed");
        }
        String stored = lines[1].substring(FOOTER_PREFIX.length());
        if (!stored.matches("[0-9a-f]{64}")
            || !stored.equals(sha256(lines[0].getBytes(StandardCharsets.UTF_8)))) {
            throw new IOException("Fixture snapshot manifest checksum mismatch");
        }
        JsonNode root = parseObject(lines[0], "Fixture snapshot manifest");
        if (!SCHEMA.equals(requiredText(root, "schema")) || requiredLong(root, "version") != VERSION) {
            throw new IOException("Fixture snapshot manifest schema is unsupported");
        }
        return root;
    }

    private static String manifestPayload(String snapshotId, Instant createdAt,
                                          EvidenceSummary summary,
                                          Map<String, FileDigest> digests) throws IOException {
        var root = MAPPER.createObjectNode();
        root.put("schema", SCHEMA);
        root.put("version", VERSION);
        root.put("snapshotId", snapshotId);
        root.put("createdAt", createdAt.toString());
        root.put("targetId", summary.targetId());
        root.put("timelineEvents", summary.timelineEvents());
        var files = root.putObject("files");
        for (String fileName : EVIDENCE_FILES) {
            FileDigest digest = digests.get(fileName);
            var node = files.putObject(fileName);
            node.put("bytes", digest.bytes());
            node.put("sha256", digest.sha256());
        }
        return MAPPER.writeValueAsString(root);
    }

    private static Map<String, FileDigest> digestEvidenceFiles(Path directory) throws IOException {
        Map<String, FileDigest> digests = new LinkedHashMap<>();
        for (String fileName : EVIDENCE_FILES) {
            Path path = requireEvidenceFile(directory, fileName);
            byte[] bytes = Files.readAllBytes(path);
            digests.put(fileName, new FileDigest(bytes.length, sha256(bytes)));
        }
        return Map.copyOf(digests);
    }

    private static Path requireEvidenceFile(Path directory, String fileName) throws IOException {
        Path path = directory.resolve(fileName).normalize();
        if (!path.getParent().equals(directory.toAbsolutePath().normalize())) {
            throw new IOException("Unsafe Fixture evidence path");
        }
        requireRegularFile(path, fileName);
        return path;
    }

    private static void requireRegularFile(Path path, String fileName) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Required Fixture evidence file is missing or unsafe: " + fileName);
        }
        long size = Files.size(path);
        if (size < 1 || size > MAX_FILE_BYTES) {
            throw new IOException("Fixture evidence file size is invalid: " + fileName);
        }
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

    private static void cleanupIncomplete(Path directory) {
        try {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
            try (var children = Files.list(directory)) {
                for (Path child : children.toList()) Files.deleteIfExists(child);
            }
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // A partial directory has no valid manifest and cannot pass verify().
        }
    }

    private static JsonNode parseObject(String json, String label) throws IOException {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) throw new IOException(label + " must be a JSON object");
            return node;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException(label + " is malformed", e);
        }
    }

    private static LinkedHashSet<String> fieldNames(JsonNode node) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String requiredText(JsonNode node, String name) throws IOException {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IOException("Missing or invalid " + name);
        }
        return value.asText();
    }

    private static long requiredLong(JsonNode node, String name) throws IOException {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IOException("Missing or invalid " + name);
        }
        return value.longValue();
    }

    private static long requiredNonNegativeLong(JsonNode node, String name) throws IOException {
        long value = requiredLong(node, name);
        if (value < 0) throw new IOException("Negative " + name);
        return value;
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + name);
        return value;
    }

    private record EvidenceSummary(String targetId, long timelineEvents) { }
}
