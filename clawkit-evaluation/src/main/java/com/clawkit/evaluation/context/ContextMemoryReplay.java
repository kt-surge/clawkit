package com.clawkit.evaluation.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import static com.clawkit.evaluation.context.EvaluationArtifacts.JSON;

/** Separate opt-in offline suite. Never reads private configuration or constructs a network provider. */
public final class ContextMemoryReplay {
    private ContextMemoryReplay() {}

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("context.memory.mode", "replay");
        String defaultOutput = "target/context-memory-replay-" + UUID.randomUUID();
        Path output = Path.of(System.getProperty("context.memory.output", defaultOutput));
        if (mode.equals("audit")) {
            var report = ReplayAudit.recompute(output);
            System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            if (!report.artifactIntegrity() || report.mechanismPassed() != report.attemptedCases()) {
                throw new IllegalStateException("offline audit failed");
            }
        } else if (mode.equals("replay")) {
            var report = run(output);
            System.out.println("Mechanism replay: " + report.mechanismPassed() + "/" + report.attemptedCases()
                + "; no live effect measurement; outputs: " + output.toAbsolutePath());
            if (!report.artifactIntegrity() || report.mechanismPassed() != report.attemptedCases()) {
                throw new IllegalStateException("mechanism replay failed; evidence retained");
            }
        } else throw new IllegalArgumentException("replay or audit required; live evaluation needs a separate authorized runner");
    }

    public static ReplayAudit.Report run(Path output) throws Exception {
        var artifacts = new EvaluationArtifacts(output);
        var catalog = ReplayCatalog.cases();
        artifacts.write("frozen-cases.json", catalog);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("suite", ReplayCatalog.VERSION);
        metadata.put("mode", "mechanism-replay");
        metadata.put("startedAt", Instant.now());
        metadata.put("datasetHash", EvaluationArtifacts.sha256(Files.readAllBytes(artifacts.resolve("frozen-cases.json"))));
        metadata.put("java", System.getProperty("java.version"));
        metadata.put("networkCalls", 0);
        metadata.put("paidModelCalls", 0);
        metadata.put("taskCompletionRate", "NOT_MEASURED");
        metadata.put("actualTokenSavings", "NOT_MEASURED");
        metadata.put("model", "scripted-fixture");
        var sources = sourceHashes();
        metadata.put("sourceHashes", sources);
        artifacts.write("manifest.json", metadata);
        var execution = new ReplayExecution(artifacts);
        for (var spec : catalog) execution.execute(spec);
        artifacts.write("source-integrity.json", Map.of("unchanged", sources.equals(sourceHashes())));
        var scores = ReplayAudit.gradeCases(artifacts.root());
        for (var score : scores) artifacts.append("instances.jsonl", score);
        artifacts.write("summary.json", Map.of("mode", "mechanism-replay", "attemptedCases", scores.size(),
            "mechanismPassed", scores.stream().filter(ReplayAudit.CaseResult::mechanismPassed).count(),
            "liveEffectMeasured", false, "actualTokenSavingsMeasured", false));
        artifacts.seal();
        // Independent recomputation returns data; it never updates the archived experiment.
        return ReplayAudit.recompute(artifacts.root());
    }

    private static Map<String, String> sourceHashes() throws Exception {
        var hashes = new java.util.TreeMap<String, String>();
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path repo = Files.exists(cwd.resolve("CLAUDE.md")) ? cwd : cwd.getParent();
        hashes.put("pom.xml", EvaluationArtifacts.sha256(Files.readAllBytes(repo.resolve("pom.xml"))));
        for (String module : java.util.List.of("clawkit-evaluation", "clawkit-context", "clawkit-memory", "clawkit-engine", "clawkit-provider", "clawkit-tools", "clawkit-observability", "clawkit-reliability")) {
            Path source = repo.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(source)) throw new IllegalStateException("required module sources absent: " + module);
            hashes.put(module + "/pom.xml", EvaluationArtifacts.sha256(Files.readAllBytes(repo.resolve(module).resolve("pom.xml"))));
            try (var paths = Files.walk(source)) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    hashes.put(repo.relativize(path).toString().replace('\\', '/'), EvaluationArtifacts.sha256(Files.readAllBytes(path)));
                }
            }
        }
        if (hashes.isEmpty()) throw new IllegalStateException("repository sources required for reproducible replay");
        return hashes;
    }
}
