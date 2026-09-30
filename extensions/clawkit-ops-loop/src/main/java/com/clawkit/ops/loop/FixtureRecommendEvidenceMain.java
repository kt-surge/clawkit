package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Generates a signed, Fixture-only A1 reconciliation evidence record. */
public final class FixtureRecommendEvidenceMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    public static final String REPORT_FILE = "a1-fixture-reconciliation-report.json";

    private FixtureRecommendEvidenceMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException(
            "Usage: FixtureRecommendEvidenceMain <empty-output-directory>");
        Path report = run(Path.of(args[0]));
        System.out.println("A1 Fixture evidence: " + report.toAbsolutePath());
        System.out.println("Provider calls: 0 (candidate is a labelled Fixture input)");
    }

    static Path run(Path outputDirectory) throws Exception {
        Files.createDirectories(outputDirectory);
        try (var entries = Files.list(outputDirectory)) {
            if (entries.findAny().isPresent()) throw new IllegalArgumentException(
                "output directory must be empty: " + outputDirectory);
        }
        ObjectNode fact = JSON.createObjectNode().put("success", true);
        fact.set("data", JSON.createObjectNode().put("rowCount", 1));
        Evidence dbLock = new Evidence("fixture-db-lock-1", "inc-fixture", EvidenceType.DB_LOCK_GRAPH,
            "fixture://db-lock", NOW, NOW, "order-api", Evidence.Kind.FACT, fact,
            "run://fixture-a1/db-lock", Evidence.Freshness.CURRENT, Evidence.Redaction.NONE,
            "2", Evidence.CollectionStatus.OBSERVED, NOW.plusSeconds(60), null);
        DiscoveryResult discovery = new DiscoveryResult("inc-fixture", "run-fixture-a1", "FIXTURE_A1",
            new EvidenceBundle("inc-fixture", "run-fixture-a1", NOW, List.of(dbLock)),
            DiscoveryStatus.COMPLETE, 1, 1, NOW);
        Diagnosis candidate = new Diagnosis("APP_DOWN", 0.98, List.of("model-candidate"), List.of(),
            List.of(), List.of(), "RESTART_SERVICE", false, "2", Diagnosis.DiagnosisStatus.CONFIRMED,
            Diagnosis.CurrentCondition.ACTIVE, NOW, Diagnosis.ResolutionAttribution.NONE);
        ReconciledDiagnosis reconciled = ReconciledDiagnosis.fromCandidate(candidate, discovery, NOW);
        DiagnosisProvenance provenance = DiagnosisProvenance.modelReconciled(candidate,
            reconciled.diagnosis(), reconciled.signals());
        if (!"DB_LOCK_WAIT".equals(reconciled.diagnosis().rootCauseCode())
            || !provenance.deterministicEvidenceChangedConclusion()) {
            throw new IllegalStateException("Fixture A1 reconciliation did not override the candidate");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("mode", "FIXTURE_A1_RECONCILIATION_CONTRACT");
        report.put("executedAt", NOW.toString());
        report.put("fixtureOnly", true);
        report.put("providerCandidateSimulated", true);
        report.put("providerCalls", 0);
        report.put("diagnosisProvenance", provenance);
        String payload = JSON.writeValueAsString(report);
        Path result = outputDirectory.resolve(REPORT_FILE);
        Path temp = result.resolveSibling(result.getFileName() + ".tmp");
        Files.writeString(temp, payload + "\n# SHA-256: " + sha256(payload) + "\n", StandardCharsets.UTF_8);
        Files.move(temp, result, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return result;
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format("%02x", value));
        return result.toString();
    }
}
