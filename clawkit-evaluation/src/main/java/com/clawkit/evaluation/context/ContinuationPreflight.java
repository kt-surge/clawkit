package com.clawkit.evaluation.context;

import com.clawkit.context.*;
import com.clawkit.context.impl.*;
import com.clawkit.engine.*;
import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.observability.FileRunRecorder;
import com.clawkit.provider.*;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.Role;
import java.util.*;

/** Runs the production first-turn context boundary and deliberately stops before every model call. */
final class ContinuationPreflight {
    record Check(String taskId, String tokenizer, Integer builtTokens, Integer compactInputTokens,
                 Integer protectedAndRecentTokens, boolean pressureReached, boolean recentWindowFits, boolean ready) {}
    record Report(boolean ready, int networkCalls, List<Check> checks) {}
    private ContinuationPreflight() {}

    static Report run(EvaluationArtifacts artifacts, ContinuationSpec spec) throws Exception {
        var checks = new ArrayList<Check>();
        for (var task : spec.tasks().stream().filter(t -> t.suite() == ContinuationSpec.Suite.COMPRESSION).toList()) {
            var prefix = "preflight/" + task.id();
            var home = artifacts.resolve(prefix + "/home");
            var workspace = artifacts.resolve(prefix + "/workspace");
            ContinuationRuntime.seed(workspace, task.agentInput().workspaceFiles());
            var tokenizer = TokenizerFactory.create(spec.settings().encoding());
            var budget = ContextBudgetPolicy.of(spec.settings().contextWindow());
            var capture = new Capture(new DefaultContextPipeline(new LadderedCompactor(null, tokenizer),
                new ContextBudgetAnalyzer(tokenizer, budget), tokenizer, budget), tokenizer, budget, spec);
            ProviderGateway prohibited = new ProviderGateway() {
                @Override public ModelResponse generate(ModelRequest request, RunScope scope) { throw new AssertionError("preflight must not dispatch a provider"); }
                @Override public ModelResponse generateStream(ModelRequest r, RunScope s, StreamObserver o) { return generate(r, s); }
            };
            try (var recorder = new FileRunRecorder(home.resolve(".clawkit"))) {
                var pipeline = new RecordingContextPipeline(new FixedToolContextPipeline(capture, tokenizer, spec.limits().outputTokensPerCall()), artifacts, prefix);
                var engine = new AgentEngine(new AgentRuntimeDependencies(prohibited, pipeline, ContinuationRuntime.tools(workspace),
                    spec.settings().contextWindow(), spec.settings().encoding(), recorder, AgentRuntimeDependencies.noopMemoryHooks(),
                    AgentRuntimeDependencies.emptySkillRuntime()), workspace.toString(), ThinkingMode.OFF, "");
                ContinuationRuntime.configure(engine, spec);
                ContinuationRuntime.loadRawHistory(engine, home, task.agentInput().histories().getFirst());
                engine.run(task.agentInput().query());
            }
            checks.add(capture.result(task.id(), tokenizer.encodingName()));
        }
        var report = new Report(checks.size() == spec.tasks().stream().filter(t -> t.suite() == ContinuationSpec.Suite.COMPRESSION).count() && checks.stream().allMatch(Check::ready), 0, List.copyOf(checks));
        artifacts.write("preflight.json", report);
        return report;
    }

    private static final class Capture implements ContextPipeline {
        private final ContextPipeline delegate;
        private final ContextBudgetAnalyzer analyzer;
        private final ContextBudgetPolicy budget;
        private final ContinuationSpec spec;
        private Integer built, compact, retained;
        private boolean pressure, fits;
        Capture(ContextPipeline delegate, Tokenizer tokenizer, ContextBudgetPolicy budget, ContinuationSpec spec) {
            this.delegate = delegate; this.analyzer = new ContextBudgetAnalyzer(tokenizer, budget); this.budget = budget; this.spec = spec;
        }
        @Override public ModelContext build(ContextRequest request) {
            var model = delegate.build(request);
            built = model.budgetReport().totalTokens();
            return model;
        }
        @Override public CompactionResult compact(CompactionRequest request) {
            var before = analyzer.analyze(request.modelContext(), request.toolDefTokens(), Map.of());
            compact = before.totalTokens();
            long hard = Math.max(0, budget.hardLimitTokens() - request.reservedOutputTokens() - request.safetyMarginTokens());
            // Above compactRatio guarantees the current ladder is genuinely exercised, not just a warning report.
            pressure = compact > budget.compactTokens();
            var minimal = new ArrayList<Message>(request.modelContext().stream().filter(m -> m.role() == Role.SYSTEM).toList());
            var turns = ContinuationBaselinePipeline.wholeTurns(request.modelContext().stream().filter(m -> m.role() != Role.SYSTEM).toList());
            turns.subList(Math.max(0, turns.size() - spec.settings().recentWholeTurns()), turns.size()).forEach(minimal::addAll);
            retained = analyzer.analyze(minimal, request.toolDefTokens(), Map.of()).totalTokens();
            fits = retained <= hard;
            var audit = new CompactionAudit("PREFLIGHT", List.of(), List.of(), List.of(), 0, 0, "PREFLIGHT_STOP_BEFORE_PROVIDER",
                CompactionLevel.L4_FAILED, "offline runtime boundary only");
            return new CompactionResult(request.modelContext(), before, before, List.of(), List.of("PREFLIGHT"),
                request.modelContext().size(), request.modelContext().size(), true, audit);
        }
        Check result(String taskId, String encoding) {
            return new Check(taskId, encoding, built, compact, retained, pressure, fits,
                built != null && compact != null && !encoding.equals("char_fallback") && pressure && fits);
        }
    }
}
