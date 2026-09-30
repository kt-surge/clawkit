package com.clawkit.ops.loop.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Strict read-only reader for the Fixture A0 observation timeline.
 *
 * <p>It deliberately accepts only the safe shape emitted by
 * {@link FixtureObserveOnlyLoop}: fixed Fixture target/run identifiers,
 * {@code fixture://} evidence references, and disabled diagnosis/provider
 * fields. A malformed or widened record fails closed instead of being shown
 * as a trustworthy replay entry.
 */
public final class FixtureObservationTimelineReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_FILE_BYTES = 1_048_576;
    private static final int MAX_ENTRIES = 100;
    private static final String FIXTURE_RUN_ID = "fixture-observe-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Set<String> ALLOWED_TYPES = Set.of(
        "OBSERVATION_INCIDENT_CREATED",
        "OBSERVATION_INCIDENT_MERGED",
        "OBSERVATION_COOLDOWN_SKIPPED",
        "OBSERVATION_HEALTHY",
        "OBSERVATION_HEALTHY_RECOVERED",
        "OBSERVATION_UNKNOWN",
        "OBSERVATION_SKIPPED_TARGET_BUSY"
    );

    private FixtureObservationTimelineReader() { }

    public record Entry(
        Instant at,
        String type,
        String runEventReference,
        String targetId,
        List<String> evidenceRefs,
        List<FixtureEvidenceFact> evidenceFacts,
        boolean diagnosisEnabled,
        boolean providerCalled
    ) {
        public Entry {
            Objects.requireNonNull(at, "at");
            requireText(type, "type");
            requireText(runEventReference, "runEventReference");
            requireText(targetId, "targetId");
            evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
            evidenceFacts = List.copyOf(Objects.requireNonNull(evidenceFacts, "evidenceFacts"));
        }
    }

    /**
     * Read at most the newest {@code limit} verified replay entries.
     *
     * @throws IOException when the file is too large, malformed, or contains
     *     an entry outside the Fixture A0 contract
     */
    public static List<Entry> read(Path path, int limit) throws IOException {
        Objects.requireNonNull(path, "path");
        if (limit < 1 || limit > MAX_ENTRIES) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_ENTRIES);
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) return List.of();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Fixture timeline is not a regular file");
        }
        if (Files.size(normalized) > MAX_FILE_BYTES) {
            throw new IOException("Fixture timeline exceeds " + MAX_FILE_BYTES + " bytes");
        }

        ArrayDeque<Entry> newest = new ArrayDeque<>(limit);
        Set<String> runReferences = new HashSet<>();
        Set<String> evidenceReferences = new HashSet<>();
        String expectedTargetId = null;
        Instant previousAt = null;
        int lineNumber = 0;
        try (var reader = Files.newBufferedReader(normalized, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                Entry entry = parse(line, lineNumber);
                if (!runReferences.add(entry.runEventReference())) {
                    throw invalid(lineNumber, "duplicate run reference");
                }
                if (expectedTargetId == null) {
                    expectedTargetId = entry.targetId();
                } else if (!expectedTargetId.equals(entry.targetId())) {
                    throw invalid(lineNumber, "mixed targets");
                }
                if (previousAt != null && entry.at().isBefore(previousAt)) {
                    throw invalid(lineNumber, "timestamp moved backwards");
                }
                previousAt = entry.at();
                for (String evidenceRef : entry.evidenceRefs()) {
                    if (!evidenceReferences.add(evidenceRef)) {
                        throw invalid(lineNumber, "duplicate evidence reference");
                    }
                }
                if (newest.size() == limit) newest.removeFirst();
                newest.addLast(entry);
            }
        }
        return List.copyOf(new ArrayList<>(newest));
    }

    private static Entry parse(String line, int lineNumber) throws IOException {
        try {
            JsonNode root = MAPPER.readTree(line);
            if (root == null || !root.isObject() || !"1".equals(text(root, "schemaVersion"))) {
                throw invalid(lineNumber, "unsupported schema");
            }
            String type = text(root, "type");
            if (!ALLOWED_TYPES.contains(type)) {
                throw invalid(lineNumber, "unexpected type");
            }
            String runRef = text(root, "runEventReference");
            if (runRef == null || !runRef.matches("run://" + FIXTURE_RUN_ID)) {
                throw invalid(lineNumber, "unsafe run reference");
            }
            JsonNode fields = root.get("fields");
            if (fields == null || !fields.isObject()) throw invalid(lineNumber, "missing fields");
            String targetId = text(fields, "targetId");
            if (targetId == null || !targetId.matches("fixture-[a-z0-9][a-z0-9_-]{0,62}")) {
                throw invalid(lineNumber, "unsafe target");
            }
            boolean diagnosisEnabled = booleanField(fields, "diagnosisEnabled", lineNumber);
            boolean providerCalled = booleanField(fields, "providerCalled", lineNumber);
            if (diagnosisEnabled || providerCalled) {
                throw invalid(lineNumber, "Fixture A0 must not enable diagnosis or Provider");
            }
            List<String> refs = evidenceRefs(fields.get("evidenceRefs"), lineNumber);
            String expectedEvidencePrefix = "fixture://" + runRef.substring("run://".length()) + "/";
            if (refs.stream().anyMatch(ref -> !ref.startsWith(expectedEvidencePrefix))) {
                throw invalid(lineNumber, "evidence reference does not belong to event run");
            }
            List<FixtureEvidenceFact> facts = evidenceFacts(fields.get("evidenceFacts"), refs, lineNumber);
            Instant at = Instant.parse(requireText(text(root, "at"), "at"));
            return new Entry(at, type, runRef, targetId, refs, facts, diagnosisEnabled, providerCalled);
        } catch (DateTimeParseException e) {
            throw invalid(lineNumber, "invalid timestamp");
        } catch (IllegalArgumentException e) {
            throw invalid(lineNumber, e.getMessage());
        }
    }

    private static List<String> evidenceRefs(JsonNode node, int lineNumber) throws IOException {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw invalid(lineNumber, "missing evidence references");
        }
        List<String> refs = new ArrayList<>();
        for (JsonNode ref : node) {
            if (!ref.isTextual() || !ref.asText().matches("fixture://" + FIXTURE_RUN_ID + "/[a-z0-9-]+")) {
                throw invalid(lineNumber, "unsafe evidence reference");
            }
            refs.add(ref.asText());
        }
        return List.copyOf(refs);
    }

    private static List<FixtureEvidenceFact> evidenceFacts(
        JsonNode node, List<String> refs, int lineNumber
    ) throws IOException {
        // Older malformed timeline tests still parse far enough to report the
        // primary structural violation. A snapshot, however, requires this
        // projection (see FixtureEvidenceSnapshot.validateEvidenceFiles).
        if (node == null) return List.of();
        if (!node.isArray() || node.size() != refs.size()) {
            throw invalid(lineNumber, "missing or incomplete evidence facts");
        }
        List<FixtureEvidenceFact> facts = new ArrayList<>();
        Set<String> factReferences = new HashSet<>();
        for (JsonNode fact : node) {
            if (fact == null || !fact.isObject() || fact.size() != 3) {
                throw invalid(lineNumber, "unsafe evidence fact shape");
            }
            JsonNode ref = fact.get("reference");
            JsonNode kind = fact.get("kind");
            JsonNode value = fact.get("value");
            if (ref == null || !ref.isTextual() || kind == null || !kind.isTextual()
                || value == null || !value.isTextual()) {
                throw invalid(lineNumber, "unsafe evidence fact value");
            }
            try {
                FixtureEvidenceFact parsed = new FixtureEvidenceFact(
                    ref.asText(), kind.asText(), value.asText());
                if (!refs.contains(parsed.reference()) || !factReferences.add(parsed.reference())) {
                    throw invalid(lineNumber, "evidence facts do not match references");
                }
                facts.add(parsed);
            } catch (IllegalArgumentException e) {
                throw invalid(lineNumber, e.getMessage());
            }
        }
        return List.copyOf(facts);
    }

    private static boolean booleanField(JsonNode fields, String name, int lineNumber) throws IOException {
        JsonNode value = fields.get(name);
        if (value == null || !value.isBoolean()) throw invalid(lineNumber, "missing " + name);
        return value.booleanValue();
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + name);
        return value;
    }

    private static IOException invalid(int lineNumber, String reason) {
        return new IOException("Invalid Fixture timeline entry at line " + lineNumber + ": " + reason);
    }
}
