package com.clawkit.evaluation.context;

import com.clawkit.engine.*;
import com.clawkit.engine.impl.DefaultMemoryHooks;
import com.clawkit.engine.impl.FileSessionStore;
import com.clawkit.memory.MemoryEntry;
import com.clawkit.memory.impl.DiskMemoryService;
import com.clawkit.tools.control.ExecutionControl;
import java.nio.file.Path;
import java.util.*;

/** Uses original runtime ingestion. Empty/failed extraction and native fallback summaries remain observable. */
final class ContinuationPreparation {
    record State(MemoryHooks hooks, SessionService sessions, Map<String, List<String>> memoryParents,
                 Map<String, String> sessionParents) {}
    private ContinuationPreparation() {}
    static State prepare(ContinuationSpec spec, ContinuationSpec.Task task, ContinuationSpec.Instance instance,
                         EvaluationArtifacts artifacts, String prefix, Path home, Path workspace,
                         ProviderGateway gateway, ExecutionControl control, ProviderRequestLimitGateway limit) throws Exception {
        if (instance.arm() == ContinuationSpec.Arm.M1_FULL_ARCHIVE_READ_GREP) {
            for (var source : task.agentInput().histories()) {
                var file = new LinkedHashMap<String, Object>();
                file.put("sourceId", source.id()); file.put("revision", source.revision()); file.put("observedAt", source.observedAt());
                file.put("messages", source.messages());
                // All original histories, not evaluator-selected relevant passages.
                artifacts.write("agents/" + instance.id() + "/workspace/archive/" + source.id() + ".json", file);
            }
        }
        if (!instance.arm().nativeMemory()) {
            return new State(AgentRuntimeDependencies.noopMemoryHooks(), null, Map.of(), Map.of());
        }
        var store = new DiskMemoryService(home.resolve(".clawkit/memory"));
        var hooks = new DefaultMemoryHooks(store, gateway, spec.settings().contextWindow(), spec.settings().encoding());
        var sessions = new SessionService(new FileSessionStore(home.resolve(".clawkit/sessions")));
        sessions.setProviderGateway(gateway);
        var memoryParents = new TreeMap<String, List<String>>();
        var sessionParents = new TreeMap<String, String>();
        var results = new ArrayList<Map<String, Object>>();
        for (var source : task.agentInput().histories()) {
            control.checkpoint();
            var before = snapshot(store);
            var scope = new RunScope("ingest-" + source.id(), null, 1, RunPhase.MEMORY_EXTRACT, ExecutionMode.REACT, control);
            var extraction = hooks.afterRun(new MemoryHooks.MemoryExtractionRequest(source.messages(), 1, scope, true, 5));
            if (limit.accountingInvalid()) throw new IllegalStateException("EVALUATION_ACCOUNTING_UNKNOWN_STOP");
            var after = snapshot(store);
            for (var entry : after.entrySet()) if (!entry.getValue().equals(before.get(entry.getKey()))) {
                // The extraction request contains this history only; an overwritten version is archived separately.
                memoryParents.put(entry.getKey(), List.of(source.id()));
            }
            var session = sessions.save(source.id(), source.messages());
            if (limit.accountingInvalid()) throw new IllegalStateException("EVALUATION_ACCOUNTING_UNKNOWN_STOP");
            sessionParents.put(session.name(), source.id());
            results.add(Map.of("sourceId", source.id(), "sourceObservedAt", source.observedAt(), "sourceRevision", source.revision(),
                "memoryResult", extraction, "session", session));
            artifacts.write(prefix + "/ingestion/" + source.id() + "-memory-revision.json", after);
            artifacts.write(prefix + "/ingestion/" + source.id() + "-result.json", results.getLast());
        }
        artifacts.write(prefix + "/ingestion/provenance.json", Map.of("memoryParents", memoryParents, "sessionParents", sessionParents,
            "attribution", "DERIVATION_ONLY_NOT_FACT_VERIFICATION", "nativeTimestamps", "INGESTION_TIME_NOT_SOURCE_OBSERVED_AT"));
        // Reopen both stores; no ephemeral ingestion object supplies the downstream answer.
        var reopened = new DefaultMemoryHooks(new DiskMemoryService(home.resolve(".clawkit/memory")), gateway,
            spec.settings().contextWindow(), spec.settings().encoding());
        var reopenedSessions = new SessionService(new FileSessionStore(home.resolve(".clawkit/sessions")));
        reopenedSessions.setProviderGateway(gateway);
        return new State(reopened, reopenedSessions, Map.copyOf(memoryParents), Map.copyOf(sessionParents));
    }
    private static Map<String, MemoryEntry> snapshot(DiskMemoryService store) {
        var entries = new TreeMap<String, MemoryEntry>();
        for (var index : store.listIndex()) {
            var entry = store.load(index.filename());
            if (entry != null) entries.put(index.filename(), entry);
        }
        return entries;
    }
}
