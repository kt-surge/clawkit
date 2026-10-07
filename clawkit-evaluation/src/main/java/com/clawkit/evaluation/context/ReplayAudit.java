package com.clawkit.evaluation.context;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.clawkit.evaluation.context.EvaluationArtifacts.JSON;

/** Recomputes from raw archived artifacts, without trusting the runner's scores or summary. */
public final class ReplayAudit {
    public record CheckResult(String pointer, boolean passed, String failureType) {}
    public record CaseResult(String id, String family, boolean mechanismPassed, List<CheckResult> checks,
                             EvidenceMetrics.Result retrieval, UsageLedger.Totals usage, String executionFailure) {}
    public record Report(boolean artifactIntegrity, int attemptedCases, int mechanismPassed,
                         List<CaseResult> cases, String effectClaims) {}

    private ReplayAudit() {}

    public static Report recompute(Path root) throws Exception {
        var cases = gradeCases(root);
        @SuppressWarnings("unchecked")
        Map<String, String> stored = JSON.readValue(root.resolve("artifact-hashes.json").toFile(), Map.class);
        JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
        boolean frozen = manifest.path("datasetHash").asText().equals(EvaluationArtifacts.sha256(Files.readAllBytes(root.resolve("frozen-cases.json"))));
        boolean unchanged = JSON.readTree(root.resolve("source-integrity.json").toFile()).path("unchanged").asBoolean();
        return new Report(stored.equals(EvaluationArtifacts.hashes(root)) && frozen && unchanged, cases.size(),
            (int) cases.stream().filter(CaseResult::mechanismPassed).count(), cases,
            "MECHANISM_REPLAY_ONLY_NO_LIVE_COMPLETION_OR_TOKEN_SAVINGS_CLAIM");
    }

    static List<CaseResult> gradeCases(Path root) throws Exception {
        var frozen = JSON.readValue(root.resolve("frozen-cases.json").toFile(), ReplayCase[].class);
        var cases = new ArrayList<CaseResult>();
        for (var spec : frozen) {
            Path instance = root.resolve("instances").resolve(spec.id());
            JsonNode observation = JSON.readTree(instance.resolve("observation.json").toFile());
            var checks = new ArrayList<CheckResult>();
            for (var check : spec.gold().checks()) {
                Path file = instance.resolve(check.artifact()).normalize();
                if (!file.startsWith(instance)) throw new IllegalArgumentException("scorer path escape");
                var node = JSON.readTree(file.toFile()).at(check.pointer());
                boolean pass = matches(node, check.relation(), check.expected());
                checks.add(new CheckResult(check.pointer(), pass, pass ? null : "MECHANISM_CONTRACT_MISMATCH"));
            }
            var sources = new ArrayList<String>();
            observation.path("retrievedSources").forEach(node -> sources.add(node.asText()));
            EvidenceMetrics.Result retrieval = null;
            if (spec.operation() == ReplayCase.Operation.MEMORY || spec.operation() == ReplayCase.Operation.FRESHNESS) {
                retrieval = EvidenceMetrics.score(sources, spec.gold().retrievalK(), spec.gold().evidenceAlternatives());
            }
            var entries = JSON.readValue(instance.resolve("usage.json").toFile(), UsageLedger.Entry[].class);
            String failure = observation.has("executionFailure") ? observation.get("executionFailure").asText() : null;
            cases.add(new CaseResult(spec.id(), spec.family(), failure == null && checks.stream().allMatch(CheckResult::passed),
                checks, retrieval, UsageLedger.total(List.of(entries)), failure));
        }
        return List.copyOf(cases);
    }

    static boolean matches(JsonNode actual, String relation, JsonNode expected) {
        if (actual.isMissingNode()) return false;
        return switch (relation) {
            case "equals" -> actual.equals(expected);
            case "contains", "not-contains" -> {
                boolean contains = actual.isArray()
                    ? java.util.stream.StreamSupport.stream(actual.spliterator(), false).anyMatch(expected::equals)
                    : actual.isTextual() && expected.isTextual() && actual.asText().contains(expected.asText());
                yield relation.equals("contains") == contains;
            }
            default -> throw new IllegalArgumentException("unknown scorer relation");
        };
    }
}
