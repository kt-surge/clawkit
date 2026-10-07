package com.clawkit.evaluation.context;

import com.clawkit.provider.*;
import com.clawkit.reliability.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Shared continuation execution; public entries select a strict split. Preparation never constructs a provider. */
public final class ContextMemoryContinuation {
    private ContextMemoryContinuation() {}
    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("context.memory.continuation.mode", "prepare");
        Path output = Path.of(System.getProperty("context.memory.continuation.output", "target/context-memory-development-" + UUID.randomUUID()));
        Path dataset = Path.of(System.getProperty("context.memory.continuation.dataset",
            EvaluationSourceSnapshot.repository().resolve("benchmarks/context-memory-dev-v1.json").toString()));
        run(mode, output, dataset, ContinuationSpec.Split.DEVELOPMENT_ONLY);
    }
    static void run(String mode, Path output, Path dataset, ContinuationSpec.Split split) throws Exception {
        if (mode.equals("audit")) {
            var report = ContinuationAudit.recompute(output);
            System.out.println(EvaluationArtifacts.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            if (!report.artifactIntegrity() || !report.outcomesAgree()) throw new IllegalStateException("continuation audit failed");
            return;
        }
        if (!mode.equals("prepare") && !mode.equals("live")) throw new IllegalArgumentException("prepare, live or audit required");
        if (mode.equals("live") && !"true".equals(System.getenv("CLAWKIT_CONTEXT_MEMORY_EVALUATION"))) {
            throw new IllegalStateException("explicit live opt-in and scoped human data/budget authorization required");
        }
        var input = EvaluationArtifacts.JSON.readTree(Files.readAllBytes(dataset));
        var spec = split == ContinuationSpec.Split.FROZEN_HOLDOUT ? ContinuationSpec.parseFrozen(input) : ContinuationSpec.parse(input);
        var artifacts = new EvaluationArtifacts(output);
        artifacts.write("frozen-data.json", input);
        artifacts.write("ordered-instances.json", spec.instances());
        var sources = EvaluationSourceSnapshot.freeze(artifacts);
        try (var variants = split == ContinuationSpec.Split.FROZEN_HOLDOUT ? FrozenContextVariants.prepare(artifacts) : null) {
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("suite", spec.version()); manifest.put("split", split.name());
            manifest.put("mode", mode.equals("live") ? "live-continuation" : "offline-preparation");
            manifest.put("startedAt", Instant.now()); manifest.put("java", System.getProperty("java.version"));
            manifest.put("datasetHash", EvaluationArtifacts.sha256(Files.readAllBytes(artifacts.resolve("frozen-data.json"))));
            manifest.put("sourceHashes", sources); manifest.put("model", spec.model()); manifest.put("settings", spec.settings());
            manifest.put("limits", spec.limits()); manifest.put("actualRequestPolicy", "temperature=0; max_tokens<=1024; reasoning disabled; blocking; retries=0; shared tool proposal permits=" + spec.settings().instanceToolCalls());
            if (variants != null) manifest.put("contextVariants", variants.metadata());
            manifest.put("cachePolicy", "provider-managed cache; interleaved balanced arm order; actual hit/miss reported when supplied");
            manifest.put("memoryIngestionPolicy", "M2 force-extracts each original source with maxEntries=5, then saves a native Session summary; original runtime prompts; ingestion timestamps retained");
            manifest.put("sourceToBinaryCorrespondence", "clean verify required; correspondence not independently proven by source hashes alone");
            manifest.put("sharedRuntimePolicy", "same Engine builds the raw context; masking and compaction belong to the selected pipeline; no common Engine-side masking");
            manifest.put("reservePolicy", "serialized dispatch; UTF-8 input bytes plus framing and output allowance; assumption checked against received usage");
            manifest.put("liveFullGateSatisfied", false); manifest.put("productionEfficiencyMeasured", false);
            artifacts.write("manifest.json", manifest);
            var preflight = ContinuationPreflight.run(artifacts, spec);
            String stop = mode.equals("prepare") ? "PREPARE_ONLY_NO_PROVIDER" : !preflight.ready() ? "PREFLIGHT_NOT_READY" : null;
            if (stop == null && !sources.equals(EvaluationSourceSnapshot.hashes(EvaluationSourceSnapshot.repository()))) stop = "SOURCE_CHANGED_BEFORE_PROVIDER";
            LLMProvider provider = null;
            if (stop == null) {
                // No private project config, user home, remote logs or server data are loaded.
                String apiKey = System.getenv("CLAWKIT_API_KEY");
                if (apiKey == null || apiKey.isBlank()) stop = "API_KEY_UNAVAILABLE";
                else provider = ProviderFactory.create(LLMConfig.builder().apiKey(apiKey).baseUrl(spec.model().endpoint())
                    .model(spec.model().model()).contextWindow(spec.settings().contextWindow()).encoding(spec.settings().encoding())
                    .maxRetries(0).connectTimeout(Duration.ofSeconds(10)).requestTimeout(Duration.ofSeconds(60)).build());
            }
            Instant roundDeadline = spec.limits().roundDeadlineSeconds() > 0 ? Instant.now().plusSeconds(spec.limits().roundDeadlineSeconds()) : Instant.MAX;
            var roundTokens = BudgetLedger.of(spec.limits().totalTokens());
            var roundCalls = WorkBudgetLedger.of(spec.limits().totalProviderCalls(), Long.MAX_VALUE);
            var rows = new ArrayList<ContinuationInstance.Row>();
            for (var instance : spec.instances()) {
                if (stop == null && !Instant.now().isBefore(roundDeadline)) stop = "ROUND_DEADLINE_REACHED";
                if (stop == null && (roundTokens.remaining() <= 0 || roundCalls.remainingProviderCalls() <= 0)) stop = "ROUND_BUDGET_EXHAUSTED";
                ContinuationInstance.Row row;
                if (stop != null) row = notRun(instance, stop);
                else {
                    try {
                        row = ContinuationInstance.execute(spec, instance, artifacts, provider, roundTokens, roundCalls, variants, roundDeadline);
                        if (row.accountingInvalid()) stop = "ACCOUNTING_UNCERTAIN_STOP_ROUND";
                    } catch (Exception e) {
                        // Partial raw calls remain on disk even if the evaluator cannot finish a row.
                        row = new ContinuationInstance.Row(instance.id(), instance.taskId(), instance.suite().name(), instance.arm().name(),
                            "EVALUATION_FAILURE", "EVALUATOR_" + e.getClass().getSimpleName(), null, null, 0, 0, true);
                        stop = "EVALUATOR_FAILURE_STOP_ROUND";
                    }
                }
                rows.add(row);
                artifacts.append("instances.jsonl", row);
            }
            artifacts.write("source-integrity.json", Map.of("unchanged", sources.equals(EvaluationSourceSnapshot.hashes(EvaluationSourceSnapshot.repository()))));
            artifacts.write("summary.json", Map.of("plannedInstances", spec.instances().size(), "recordedInstances", rows.size(),
                "attemptedInstances", rows.stream().filter(r -> !r.status().equals("NOT_RUN")).count(),
                "passed", rows.stream().filter(r -> r.status().equals("PASS")).count(),
                "notRun", rows.stream().filter(r -> r.status().equals("NOT_RUN")).count(),
                "stopReason", stop == null ? "ROUND_COMPLETED" : stop,
                "actualTokenSavingsMeasured", false, "formalEffectClaimsAllowed", false));
            artifacts.seal();
            if (mode.equals("prepare")) {
                System.out.println("Offline preparation: ready=" + preflight.ready() + "; zero model calls; outputs=" + artifacts.root());
                if (!preflight.ready()) throw new IllegalStateException("continuation pressure preflight failed; inputs retained");
            } else {
                var audit = ContinuationAudit.recompute(artifacts.root());
                System.out.println(split.name() + " continuation: " + audit.passed() + "/" + audit.planned() + "; outputs=" + artifacts.root());
                if (!audit.artifactIntegrity() || !audit.outcomesAgree() || rows.stream().anyMatch(r -> r.status().equals("NOT_RUN"))) {
                    throw new IllegalStateException("continuation round incomplete or audit failed; all evidence retained");
                }
            }
        }
    }

    private static ContinuationInstance.Row notRun(ContinuationSpec.Instance instance, String reason) {
        return new ContinuationInstance.Row(instance.id(), instance.taskId(), instance.suite().name(), instance.arm().name(),
            "NOT_RUN", reason, null, null, 0, 0, false);
    }
}
