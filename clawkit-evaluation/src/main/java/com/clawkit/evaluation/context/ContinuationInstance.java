package com.clawkit.evaluation.context;

import com.clawkit.context.*;
import com.clawkit.context.impl.*;
import com.clawkit.engine.*;
import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.engine.impl.ObservingProviderGateway;
import com.clawkit.observability.FileRunRecorder;
import com.clawkit.provider.LLMProvider;
import com.clawkit.reliability.*;
import com.clawkit.tools.control.WorkBudget;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** One isolated development instance. Every failure stays in the denominator and every phase shares its cap. */
final class ContinuationInstance {
    record Observation(String taskRoot, List<String> runIds, String executionFailure, String taskStatus,
                       long durationMs, long dispatchedProviderCalls, boolean accountingInvalid) {}
    record Row(String id, String taskId, String suite, String arm, String status, String failureType,
               ContinuationTaskScorer.Result outcome, UsageLedger.Totals usage, long durationMs,
               long dispatchedProviderCalls, boolean accountingInvalid) {}
    private ContinuationInstance() {}

    static Row execute(ContinuationSpec spec, ContinuationSpec.Instance instance, EvaluationArtifacts artifacts,
                       LLMProvider provider, BudgetLedger roundTokens, WorkBudget roundCalls) throws Exception {
        return execute(spec, instance, artifacts, provider, roundTokens, roundCalls, null, Instant.MAX);
    }
    static Row execute(ContinuationSpec spec, ContinuationSpec.Instance instance, EvaluationArtifacts artifacts,
                       LLMProvider provider, BudgetLedger roundTokens, WorkBudget roundCalls,
                       FrozenContextVariants variants, Instant roundDeadline) throws Exception {
        if (instance.arm() == ContinuationSpec.Arm.C2_FROZEN_ORIGINAL_CONTEXT && variants == null)
            throw new IllegalArgumentException("original Context requires the verified original artifact");
        var task = spec.task(instance);
        String prefix = "instances/" + instance.id();
        Path home = artifacts.resolve("agents/" + instance.id() + "/home");
        Path work = artifacts.resolve("agents/" + instance.id() + "/workspace");
        ContinuationRuntime.seed(work, task.agentInput().workspaceFiles());
        artifacts.write(prefix + "/agent-input.json", task.agentInput());
        var tokenizer = TokenizerFactory.create(spec.settings().encoding());
        var budget = ContextBudgetPolicy.of(spec.settings().contextWindow());
        var proposedDeadline = Instant.now().plusSeconds(spec.settings().instanceDeadlineSeconds());
        var instanceControl = CancellationTree.root(proposedDeadline.isBefore(roundDeadline) ? proposedDeadline : roundDeadline,
            roundTokens.childCapped(spec.limits().instanceTotalTokens()),
            WorkBudgetLedger.of(spec.limits().instanceProviderCalls(), spec.settings().instanceToolCalls()));
        var ledger = new UsageLedger();
        var started = System.nanoTime();
        String failure = null;
        ProviderRequestLimitGateway limit = null;
        ContinuationEventRecorder events;
        try (var recorder = new FileRunRecorder(home.resolve(".clawkit"))) {
            events = new ContinuationEventRecorder(recorder, artifacts, prefix);
            var raw = new EvaluationGateway(new ObservingProviderGateway(provider, events), artifacts, ledger, prefix);
            limit = new ProviderRequestLimitGateway(new RoundCallLimitGateway(raw, roundCalls), spec.limits().outputTokensPerCall(),
                spec.settings().framingReserveTokens(), true);
            ProviderGateway gateway = new BudgetedEvaluationGateway(new FixedToolEvaluationGateway(limit, instanceControl.workBudget()), instanceControl);
            try {
                var prepared = ContinuationPreparation.prepare(spec, task, instance, artifacts, prefix, home, work,
                    gateway, instanceControl, limit);
                var scope = new RunScope("summary-" + instance.id(), null, 0, RunPhase.COMPACT, ExecutionMode.REACT, instanceControl);
                Summarizer summarizer = messages -> gateway.generate(com.clawkit.provider.ModelRequest.of(messages, List.of()), scope).content();
                ContextPipeline production = variants == null ? new DefaultContextPipeline(new LadderedCompactor(summarizer, tokenizer),
                    new ContextBudgetAnalyzer(tokenizer, budget), tokenizer, budget) : variants.create(instance.arm(), summarizer, tokenizer,
                        new ContextBudgetAnalyzer(tokenizer, budget), budget);
                var mode = switch (instance.arm()) {
                    case C0_RECENT_WHOLE_TURNS -> ContinuationBaselinePipeline.Mode.RECENT_WHOLE_TURNS;
                    case C1_ROLLING_SUMMARY -> ContinuationBaselinePipeline.Mode.ROLLING_SUMMARY;
                    default -> ContinuationBaselinePipeline.Mode.CURRENT;
                };
                ContextPipeline baseline = new ContinuationBaselinePipeline(production, new ContextBudgetAnalyzer(tokenizer, budget),
                    budget, mode, spec.settings().recentWholeTurns(), gateway, scope);
                ContextPipeline pipeline = new FixedToolContextPipeline(new InstanceBudgetContextPipeline(baseline, instanceControl),
                    tokenizer, spec.limits().outputTokensPerCall());
                pipeline = new RecordingContextPipeline(pipeline, artifacts, prefix);
                var engine = new AgentEngine(new AgentRuntimeDependencies(gateway, pipeline, ContinuationRuntime.tools(work),
                    spec.settings().contextWindow(), spec.settings().encoding(), events, prepared.hooks(),
                    AgentRuntimeDependencies.emptySkillRuntime()), work.toString(), ThinkingMode.OFF, prepared.hooks().memoryIndex());
                ContinuationRuntime.configure(engine, spec);
                if (task.suite() == ContinuationSpec.Suite.COMPRESSION) {
                    ContinuationRuntime.loadRawHistory(engine, home, task.agentInput().histories().getFirst());
                } else if (prepared.sessions() != null) engine.setSessionService(prepared.sessions());
                engine.run(task.agentInput().query());
            } catch (Exception e) {
                failure = e.getClass().getSimpleName();
                artifacts.write(prefix + "/runtime-failure.json", Map.of("type", e.getClass().getName(), "stack", java.util.Arrays.stream(e.getStackTrace()).limit(16).toList()));
            }
        }
        long duration = (System.nanoTime() - started) / 1_000_000;
        boolean unknown = limit != null && limit.accountingInvalid();
        String taskStatus;
        ContinuationTaskScorer.Result score = null;
        try {
            taskStatus = ContinuationTraceReader.taskStatus(home, events.taskRoot());
            var tools = ContinuationTraceReader.read(home, artifacts.resolve(prefix), events.runIds());
            artifacts.write(prefix + "/executed-tools.json", tools);
            score = ContinuationTaskScorer.score(work, task.outcomeGold(), task.agentInput().workspaceFiles(), tools, taskStatus);
        } catch (Exception e) {
            taskStatus = "EVALUATOR_FAILURE"; failure = "EVIDENCE_" + e.getClass().getSimpleName();
            artifacts.write(prefix + "/evidence-failure.json", Map.of("type", e.getClass().getName(), "stack", java.util.Arrays.stream(e.getStackTrace()).limit(16).toList()));
        }
        artifacts.write(prefix + "/observation.json", new Observation(events.taskRoot(), events.runIds(), failure, taskStatus,
            duration, events.dispatchedProviderCalls(), unknown));
        artifacts.write(prefix + "/usage.json", ledger.entries());
        artifacts.write(prefix + "/usage-totals.json", ledger.totals());
        artifacts.write(prefix + "/workspace-hashes.json", EvaluationArtifacts.hashes(work));
        String status = failure != null || unknown ? "EVALUATION_FAILURE" : score != null && score.taskCompleted() ? "PASS" : "FAIL";
        return new Row(instance.id(), task.id(), instance.suite().name(), instance.arm().name(), status, failure,
            score, ledger.totals(), duration, events.dispatchedProviderCalls(), unknown);
    }
}
