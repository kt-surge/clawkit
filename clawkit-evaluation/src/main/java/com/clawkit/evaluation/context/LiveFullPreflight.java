package com.clawkit.evaluation.context;

import com.clawkit.context.*;
import com.clawkit.context.impl.TokenizerFactory;
import com.clawkit.engine.*;
import com.clawkit.tools.RunToolScope;
import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.provider.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Captures the first dispatch boundary without constructing or invoking any model provider. */
final class LiveFullPreflight {
    record Result(boolean ready,int instances,int capturedRequests,int providerCalls,boolean initialHistoryEmpty,List<Map<String,Object>> checks) {}
    private static final class CaptureOnly extends RuntimeException {}
    static Result run(LiveFullSpec spec,EvaluationArtifacts artifacts,FrozenContextVariants variants) throws Exception {
        var checks=new ArrayList<Map<String,Object>>();boolean ready=true;int count=0;
        for(var instance:spec.instances()) {
            var task=spec.task(instance);String prefix="preflight/"+instance.id();var work=artifacts.resolve(prefix+"/workspace");
            var environment=new LiveFullEnvironment(work,task.agentInput(),task.environment(),artifacts,prefix);
            var captured=new AtomicReference<ModelRequest>();
            ProviderGateway capture=new ProviderGateway(){
                @Override public ModelResponse generate(ModelRequest request,RunScope scope){if(!captured.compareAndSet(null,request))throw new IllegalStateException("multiple preparation dispatches");throw new CaptureOnly();}
                @Override public ModelResponse generateStream(ModelRequest request,RunScope scope,StreamObserver observer){return generate(request,scope);}
            };
            var limit=new ProviderRequestLimitGateway(capture,spec.limits().outputTokensPerCall(),spec.settings().framingReserveTokens(),false);
            ProviderGateway gateway=new FixedToolEvaluationGateway(limit,null);
            var control=com.clawkit.reliability.CancellationTree.root(null,com.clawkit.reliability.BudgetLedger.of(spec.limits().instanceTotalTokens()));
            var tokenizer=TokenizerFactory.create(spec.settings().encoding());var budget=ContextBudgetPolicy.of(spec.settings().contextWindow());
            var production=variants.create(instance.arm(),messages->{throw new IllegalStateException("empty initial history must not summarize");},tokenizer,new ContextBudgetAnalyzer(tokenizer,budget),budget);
            ContextPipeline selected=instance.arm()==ContinuationSpec.Arm.C1_ROLLING_SUMMARY?new LiveFullRollingPipeline(production,new ContextBudgetAnalyzer(tokenizer,budget),budget,capture,new RunScope("preflight-summary",null,0,RunPhase.COMPACT,ExecutionMode.REACT)):production;
            ContextPipeline pipeline=new FixedToolContextPipeline(new InstanceBudgetContextPipeline(selected,control,tokenizer,limit,plan->{
                try{artifacts.append(prefix+"/input-budget-plans.jsonl",plan);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            }),tokenizer,spec.limits().outputTokensPerCall());
            var engine=new AgentEngine(new AgentRuntimeDependencies(gateway,pipeline,environment.tools(),spec.settings().contextWindow(),spec.settings().encoding()),work.toString(),ThinkingMode.OFF,"");
            engine.setWorkspaceRules(LiveFullInstance.RULES);
            var acceptance=spec.acceptanceEnabled()?new LiveFullPublicAcceptance(work,task.agentInput(),task.environment()):null;
            try {engine.run(task.agentInput().query(),RunToolScope.ALL,acceptance==null?TaskCompletionCheck.NONE:acceptance);}catch(RuntimeException ignored){} // CaptureOnly is an intentional before-provider stop.
            var request=captured.get();boolean match=request!=null&&request.messages().stream().filter(m->m.role()==com.clawkit.tools.schema.Role.USER).count()==1
                &&request.messages().stream().anyMatch(m->m.role()==com.clawkit.tools.schema.Role.USER&&task.agentInput().query().equals(m.content()))
                &&request.messages().stream().noneMatch(m->m.role()==com.clawkit.tools.schema.Role.TOOL||m.role()==com.clawkit.tools.schema.Role.ASSISTANT)
                &&request.tools().stream().allMatch(t->FixedToolEvaluationGateway.TASK_TOOLS.contains(t.name()))&&request.tools().size()==4;
            if(request!=null){count++;artifacts.write(prefix+"/captured-boundary.json",request);}
            ready &= match;checks.add(Map.of("instance",instance.id(),"initialBoundaryValid",match,"providerConstructed",false,"historyMessages",0,"completionCheckConfigured",acceptance!=null,"completionCheckId",acceptance==null?"NONE":LiveFullPublicAcceptance.ID));
        }
        var result=new Result(ready,spec.instances().size(),count,0,true,List.copyOf(checks));artifacts.write("preflight.json",result);return result;
    }
}
