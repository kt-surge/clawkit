package com.clawkit.evaluation.context;

import com.clawkit.context.*;
import com.clawkit.context.impl.TokenizerFactory;
import com.clawkit.engine.*;
import com.clawkit.tools.RunToolScope;
import com.clawkit.engine.impl.*;
import com.clawkit.observability.FileRunRecorder;
import com.clawkit.provider.LLMProvider;
import com.clawkit.reliability.*;
import com.clawkit.tools.control.WorkBudget;
import java.time.*;
import java.util.*;

final class LiveFullInstance {
    record Row(String id,String taskId,String arm,String status,String failureType,LiveFullEvidence.Result evidence,long durationMs) {}
    static final String RULES="当前是隔离合成本地任务，只使用实际提供的四个文件工具；以工作区公开规则执行，只有 preview/ 可写。维护既有源文件和历史完成记录，遇到快照变化重新采证。不联网，不执行修复。不要调用未提供的 task/todo/memory/bash 工具。完成全部文件及结构校验后结束。";
    static Row execute(LiveFullSpec spec,LiveFullSpec.Instance instance,EvaluationArtifacts artifacts,LLMProvider provider,
                       BudgetLedger roundTokens,WorkBudget roundCalls,FrozenContextVariants variants,Instant deadline) throws Exception {
        var task=spec.task(instance);String prefix="instances/"+instance.id();var home=artifacts.resolve("agents/"+instance.id()+"/home");var work=artifacts.resolve("agents/"+instance.id()+"/workspace");
        var environment=new LiveFullEnvironment(work,task.agentInput(),task.environment(),artifacts,prefix);artifacts.write(prefix+"/agent-input.json",task.agentInput());
        var tokenizer=TokenizerFactory.create(spec.settings().encoding());var budget=ContextBudgetPolicy.of(spec.settings().contextWindow());
        Instant local=Instant.now().plusSeconds(spec.settings().instanceDeadlineSeconds());var control=CancellationTree.root(local.isBefore(deadline)?local:deadline,
            roundTokens.childCapped(spec.limits().instanceTotalTokens()),WorkBudgetLedger.of(spec.limits().instanceProviderCalls(),spec.settings().instanceToolCalls()));
        var ledger=new UsageLedger();long started=System.nanoTime();String failure=null;
        try(var recorder=new FileRunRecorder(home.resolve(".clawkit"))) {
            var events=new ContinuationEventRecorder(recorder,artifacts,prefix);
            ProviderGateway raw=new EvaluationGateway(new ObservingProviderGateway(provider,events),artifacts,ledger,prefix);
            var limit=new ProviderRequestLimitGateway(new RoundCallLimitGateway(raw,roundCalls),spec.limits().outputTokensPerCall(),spec.settings().framingReserveTokens(),true);
            ProviderGateway gateway=new BudgetedEvaluationGateway(new FixedToolEvaluationGateway(limit,control.workBudget()),control);
            var summaryScope=new RunScope("full-summary-"+instance.id(),null,0,RunPhase.COMPACT,ExecutionMode.REACT,control);
            var production=variants.create(instance.arm(),messages->gateway.generate(com.clawkit.provider.ModelRequest.of(messages,List.of()),summaryScope).content(),tokenizer,new ContextBudgetAnalyzer(tokenizer,budget),budget);
            ContextPipeline selected=instance.arm()==ContinuationSpec.Arm.C1_ROLLING_SUMMARY?new LiveFullRollingPipeline(production,new ContextBudgetAnalyzer(tokenizer,budget),budget,gateway,summaryScope):production;
            ContextPipeline pipeline=new RecordingContextPipeline(new FixedToolContextPipeline(new InstanceBudgetContextPipeline(selected,control,tokenizer,limit,plan->{
                try{artifacts.append(prefix+"/input-budget-plans.jsonl",plan);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            }),tokenizer,spec.limits().outputTokensPerCall()),artifacts,prefix);
            var engine=new AgentEngine(new AgentRuntimeDependencies(gateway,pipeline,environment.tools(),spec.settings().contextWindow(),spec.settings().encoding(),events,
                AgentRuntimeDependencies.noopMemoryHooks(),AgentRuntimeDependencies.emptySkillRuntime()),work.toString(),ThinkingMode.OFF,"");
            engine.setWorkspaceRules(RULES);engine.setRunLimits(Duration.ofSeconds(spec.settings().instanceDeadlineSeconds()),spec.limits().instanceTotalTokens(),(long)spec.limits().instanceProviderCalls(),(long)spec.settings().instanceToolCalls());
            var acceptance=spec.acceptanceEnabled()?new LiveFullPublicAcceptance(work,task.agentInput(),task.environment()):null;
            try { engine.run(task.agentInput().query(),RunToolScope.ALL,acceptance==null?TaskCompletionCheck.NONE:acceptance); }
            catch(Exception e){failure=e.getClass().getSimpleName();artifacts.write(prefix+"/runtime-failure.json",Map.of("type",e.getClass().getName(),"message",String.valueOf(e.getMessage()),"stack",Arrays.stream(e.getStackTrace()).limit(16).toList()));}
            finally { if(acceptance!=null)artifacts.write(prefix+"/completion-checks.json",acceptance.receipts()); }
        }
        artifacts.write(prefix+"/usage.json",ledger.entries());artifacts.write(prefix+"/workspace-hashes.json",EvaluationArtifacts.hashes(work));
        LiveFullEvidence.Result evidence=null;
        try{evidence=LiveFullEvidence.recompute(artifacts.root(),spec,instance);}
        catch(Exception e){failure="EVIDENCE_"+e.getClass().getSimpleName();artifacts.write(prefix+"/evidence-failure.json",Map.of("type",e.getClass().getName(),"message",String.valueOf(e.getMessage()),"stack",Arrays.stream(e.getStackTrace()).limit(16).toList()));}
        String status=failure!=null||evidence==null||evidence.accountingInvalid()?"EVALUATION_FAILURE":evidence.liveFullGateSatisfied()?"PASS":"FAIL";
        return new Row(instance.id(),instance.taskId(),instance.arm().name(),status,failure,evidence,(System.nanoTime()-started)/1_000_000);
    }
}
