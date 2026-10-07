package com.clawkit.evaluation.context;

import com.clawkit.context.*;
import com.clawkit.context.impl.*;
import com.clawkit.engine.*;
import com.clawkit.engine.impl.DefaultMemoryHooks;
import com.clawkit.engine.impl.FileSessionStore;
import com.clawkit.engine.impl.ObservingProviderGateway;
import com.clawkit.memory.MemoryEntry;
import com.clawkit.memory.impl.DiskMemoryService;
import com.clawkit.observability.FileRunRecorder;
import com.clawkit.provider.*;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.clawkit.evaluation.context.ReplayCase.*;

/** Executes production components without consulting gold. All fake model calls are marked unavailable usage. */
final class ReplayExecution {
    record Origin(String kind, String artifact, String hash, int revision, List<String> parents,
                  String attribution, java.time.Instant observedAt) {}
    private final EvaluationArtifacts artifacts;

    ReplayExecution(EvaluationArtifacts artifacts) { this.artifacts = artifacts; }

    void execute(ReplayCase spec) throws Exception {
        String prefix = "instances/" + spec.id();
        Path home = artifacts.resolve("agents/" + spec.id() + "/home");
        Path work = artifacts.resolve("agents/" + spec.id() + "/workspace");
        Files.createDirectories(home);
        Files.createDirectories(work);
        for (var file : spec.input().workspaceFiles().entrySet()) {
            Path target = work.resolve(file.getKey()).normalize();
            if (!target.startsWith(work)) throw new IllegalArgumentException("workspace escape");
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue(), java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        artifacts.write(prefix + "/agent-input.json", spec.input());
        var ledger = new UsageLedger();
        var observations = new LinkedHashMap<String, Object>();
        var origins = new ArrayList<Origin>();
        for (var source : spec.input().histories()) {
            byte[] bytes = EvaluationArtifacts.JSON.writeValueAsBytes(source.messages());
            origins.add(new Origin("RAW_SESSION", source.id(), EvaluationArtifacts.sha256(bytes), source.revision(),
                List.of(source.id()), "RAW_SOURCE", source.observedAt()));
        }
        try (var recorder = new FileRunRecorder(home.resolve(".clawkit"))) {
            var provider = new ReplayProvider(spec.script(), spec.operation() == Operation.COMPACT);
            var gateway = new EvaluationGateway(new ObservingProviderGateway(provider, recorder), artifacts, ledger, prefix);
            switch (spec.operation()) {
                case COMPACT, BUDGET_FAILURE -> compact(spec, gateway, observations, origins);
                case SESSION_RESUME -> resume(spec, gateway, home, work, observations);
                case MEMORY, FRESHNESS -> memory(spec, gateway, home, work, observations, origins);
            }
            observations.put("providerCalls", ledger.entries().size());
        } catch (Exception e) {
            // Keep every attempted case, including partial transcripts and usage.
            observations.put("executionFailure", e.getClass().getSimpleName());
        }
        artifacts.write(prefix + "/observation.json", observations);
        artifacts.write(prefix + "/origins.json", origins);
        artifacts.write(prefix + "/usage.json", ledger.entries());
        artifacts.write(prefix + "/usage-totals.json", ledger.totals());
    }

    private void compact(ReplayCase spec, ProviderGateway gateway, Map<String, Object> observed, List<Origin> origins) throws Exception {
        var tokenizer = new CharFallbackTokenizer();
        var budget = ContextBudgetPolicy.of(spec.input().contextWindow());
        var scope = new RunScope(spec.id(), null, 0, RunPhase.COMPACT, ExecutionMode.REACT);
        Summarizer summarizer = messages -> gateway.generate(ModelRequest.of(messages, List.of()), scope).content();
        var pipeline = new DefaultContextPipeline(new LadderedCompactor(summarizer, tokenizer),
            new ContextBudgetAnalyzer(tokenizer, budget), tokenizer, budget,
            new AdaptiveCompactionPolicy(0, 64, spec.input().anchorRatio(), 1_000));
        var raw = spec.input().histories().stream().flatMap(h -> h.messages().stream()).toList();
        long remaining = spec.operation() == Operation.BUDGET_FAILURE ? 200 : Long.MAX_VALUE;
        var result = pipeline.compact(new CompactionRequest(raw, 0, 29,
            new CompactionHint(CompactionProfile.GENERAL, spec.input().anchors()), 0, 0, remaining));
        observed.put("remainingRunTokenBudget", remaining);
        observed.put("compaction", result);
        observed.put("failureCode", result.audit().failureCode());
        observed.put("level", result.audit().level().name());
        observed.put("retainedAnchorIds", result.audit().retainedAnchorIds());
        String anchors = result.messages().stream().map(Message::content).filter(Objects::nonNull)
            .filter(t -> t.startsWith("[Runtime][Compaction Anchors]")).findFirst().orElse("");
        observed.put("anchorText", anchors);
        observed.put("tokenizer", tokenizer.encodingName());
        observed.put("tokenCountsAreEstimates", true);
        // Compression ancestry is conservative: it never asserts each output fact has a verified source.
        origins.add(new Origin("COMPACTED_SESSION", "compaction.messages",
            EvaluationArtifacts.sha256(EvaluationArtifacts.JSON.writeValueAsBytes(result.messages())), 1,
            spec.input().histories().stream().map(HistorySource::id).toList(), "DERIVATION_ONLY_NOT_FACT_VERIFICATION", null));
    }

    private void resume(ReplayCase spec, ProviderGateway gateway, Path home, Path work, Map<String, Object> observed) throws Exception {
        var sessions = new SessionService(new FileSessionStore(home.resolve(".clawkit/sessions")));
        sessions.setProviderGateway(gateway);
        var source = spec.input().histories().getFirst();
        var meta = sessions.save(spec.id(), source.messages());
        // Reopen persistence to prove this is not merely an in-process copy.
        var reopened = new SessionService(new FileSessionStore(home.resolve(".clawkit/sessions")));
        observed.put("loadedHistory", reopened.load(meta.id()));
        observed.put("sessionSummary", reopened.list().getFirst().summary());
        observed.put("workspaceText", Files.readString(work.resolve(".clawkit/todo.md")));
    }

    private void memory(ReplayCase spec, ProviderGateway gateway, Path home, Path work,
                         Map<String, Object> observed, List<Origin> origins) throws Exception {
        var store = new DiskMemoryService(home.resolve(".clawkit/memory"));
        var hooks = new DefaultMemoryHooks(store, gateway, 128_000, "cl100k_base");
        Map<String, List<String>> memoryParents = new HashMap<>();
        var ingestion = new ArrayList<MemoryHooks.MemorySaveResult>();
        int version = 0;
        for (var source : spec.input().histories()) {
            var before = snapshot(store);
            var scope = new RunScope(spec.id() + "-ingest-" + source.id(), null, 1, RunPhase.MEMORY_EXTRACT, ExecutionMode.REACT);
            ingestion.add(hooks.afterRun(new MemoryHooks.MemoryExtractionRequest(source.messages(), 1, scope, true, 5)));
            var after = snapshot(store);
            for (var entry : after.entrySet()) {
                if (entry.getValue().equals(before.get(entry.getKey()))) continue;
                memoryParents.put(entry.getKey(), List.of(source.id()));
                origins.add(new Origin("DERIVED_MEMORY", entry.getKey(), EvaluationArtifacts.sha256(
                    EvaluationArtifacts.JSON.writeValueAsBytes(entry.getValue())), ++version, List.of(source.id()),
                    "MODEL_DERIVATION_NOT_FACT_VERIFICATION", source.observedAt()));
            }
            artifacts.write("instances/" + spec.id() + "/memory-revisions/" + source.id() + ".json", after);
        }
        // New hooks and store for recall; ephemeral ingestion state cannot carry the answer.
        var reopened = new DiskMemoryService(home.resolve(".clawkit/memory"));
        var recallHooks = new DefaultMemoryHooks(reopened, gateway, 128_000, "cl100k_base");
        var recall = recallHooks.beforeRun(new MemoryHooks.MemoryRecallRequest(spec.input().query(), 5, Set.of()));
        var retrievedSources = new ArrayList<String>();
        var entries = snapshot(reopened);
        for (Message message : recall) {
            for (var entry : entries.entrySet()) {
                if (message.content().startsWith("[Relevant Memory: " + entry.getValue().name() + "]\n")) {
                    retrievedSources.addAll(memoryParents.getOrDefault(entry.getKey(), List.of()));
                }
            }
        }
        observed.put("ingestion", ingestion);
        observed.put("memoryEntries", entries.values());
        observed.put("recall", recall);
        observed.put("retrievedSources", retrievedSources);
        if (spec.operation() == Operation.FRESHNESS) {
            observed.put("currentEvidence", EvaluationArtifacts.JSON.readTree(Files.readString(work.resolve("current-health.json"))));
        }
        var tokenizer = new CharFallbackTokenizer();
        var workspace = spec.operation() == Operation.FRESHNESS
            ? List.of(Message.system("[Workspace]\n" + Files.readString(work.resolve("current-health.json")))) : List.<Message>of();
        var budget = ContextBudgetPolicy.of(128_000);
        var context = new DefaultContextPipeline(new LadderedCompactor(null, tokenizer),
            new ContextBudgetAnalyzer(tokenizer, budget), tokenizer, budget).build(new ContextRequest(
                "Evaluation context", List.of(Message.user(spec.input().query())), workspace,
                List.of(), recall, List.of(), List.of(), budget));
        observed.put("builtContext", context);
        artifacts.write("instances/" + spec.id() + "/context-partitions.json", context.fragments());
    }

    private static Map<String, MemoryEntry> snapshot(DiskMemoryService store) {
        var result = new TreeMap<String, MemoryEntry>();
        for (var entry : store.listIndex()) result.put(entry.filename(), store.load(entry.filename()));
        return result;
    }

    private static final class ReplayProvider implements LLMProvider {
        private final List<String> responses;
        private final boolean repeat;
        private int index;
        ReplayProvider(List<String> responses, boolean repeat) { this.responses = List.copyOf(responses); this.repeat = repeat; }
        @Override public synchronized Message generate(List<Message> messages, List<ToolDefinition> tools) {
            if (responses.isEmpty() || (!repeat && index >= responses.size()) || index >= 64) {
                throw new LLMException("REPLAY_SCRIPT_EXHAUSTED");
            }
            String response = responses.get(repeat ? 0 : index);
            index++;
            return Message.assistant(response);
        }
    }
}
