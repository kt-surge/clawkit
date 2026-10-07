package com.clawkit.evaluation.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Freezes build and source inputs as bytes, rather than relying on a mutable checkout or commit alone. */
final class EvaluationSourceSnapshot {
    private static final List<String> MODULES = List.of("clawkit-evaluation", "clawkit-context", "clawkit-memory",
        "clawkit-engine", "clawkit-provider", "clawkit-tools", "clawkit-observability", "clawkit-reliability");
    private EvaluationSourceSnapshot() {}
    static Path repository() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cursor != null && !Files.isRegularFile(cursor.resolve("CLAUDE.md"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("repository sources required");
        return cursor;
    }
    static Map<String, String> hashes(Path repository) throws Exception {
        var hashes = new TreeMap<String, String>();
        hashes.put("pom.xml", EvaluationArtifacts.sha256(Files.readAllBytes(repository.resolve("pom.xml"))));
        for (var contract : List.of("CLAUDE.md", "DESIGN.md", "SECURITY.md", "docs/context-memory-evaluation.md", "docs/context-memory-frozen-validation-plan.md", "docs/context-memory-live-full-plan.md", "docs/context-memory-file-checkpoint.md",
                "scripts/build-context-memory-live-full.mjs", "scripts/build-context-memory-acceptance.mjs",
                "scripts/audit-context-memory-replay.mjs", "scripts/review-context-memory-continuation.mjs",
                "scripts/test-context-memory-review.mjs", "scripts/analyze-context-memory-frozen.mjs",
                "scripts/test-context-memory-frozen-statistics.mjs")) {
            hashes.put(contract, EvaluationArtifacts.sha256(Files.readAllBytes(repository.resolve(contract))));
        }
        for (String module : MODULES) {
            hashes.put(module + "/pom.xml", EvaluationArtifacts.sha256(Files.readAllBytes(repository.resolve(module + "/pom.xml"))));
            Path source = repository.resolve(module + "/src");
            if (!Files.isDirectory(source)) throw new IllegalStateException("required source absent: " + module);
            try (var files = Files.walk(source)) {
                for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                    if (Files.isSymbolicLink(file)) throw new IllegalStateException("symbolic source input refused");
                    hashes.put(repository.relativize(file).toString().replace('\\', '/'), EvaluationArtifacts.sha256(Files.readAllBytes(file)));
                }
            }
        }
        Path baseline = repository.resolve(FrozenContextVariants.BASELINE);
        try (var files = Files.walk(baseline)) {
            for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                if (Files.isSymbolicLink(file)) throw new IllegalStateException("symbolic baseline refused");
                hashes.put(repository.relativize(file).toString().replace('\\', '/'), EvaluationArtifacts.sha256(Files.readAllBytes(file)));
            }
        }
        return hashes;
    }
    static Map<String, String> freeze(EvaluationArtifacts artifacts) throws Exception {
        Path repo = repository();
        var hashes = hashes(repo);
        for (var entry : hashes.entrySet()) {
            byte[] bytes = Files.readAllBytes(repo.resolve(entry.getKey()));
            if (!EvaluationArtifacts.sha256(bytes).equals(entry.getValue())) throw new IllegalStateException("source changed during freeze");
            Path target = artifacts.resolve("source-inputs/" + entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        }
        return hashes;
    }
}
