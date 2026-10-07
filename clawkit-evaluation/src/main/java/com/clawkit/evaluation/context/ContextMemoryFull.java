package com.clawkit.evaluation.context;

import com.clawkit.provider.*;
import com.clawkit.reliability.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Independent full task entry. Defaults to offline preparation; requires its own scoped live opt-in. */
public final class ContextMemoryFull {
    private ContextMemoryFull() {}
    public static void main(String[] args)throws Exception {
        String mode=System.getProperty("context.memory.full.mode","prepare");Path output=Path.of(System.getProperty("context.memory.full.output","target/context-memory-full-"+UUID.randomUUID()));
        Path dataset=Path.of(System.getProperty("context.memory.full.dataset",EvaluationSourceSnapshot.repository().resolve("benchmarks/context-memory-live-full-v1.json").toString()));
        run(mode,output,dataset);
    }
    static void run(String mode,Path output,Path dataset)throws Exception {
        if(mode.equals("audit")){var result=LiveFullAudit.recompute(output);System.out.println(EvaluationArtifacts.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));if(!result.artifactIntegrity()||!result.outcomesAgree())throw new IllegalStateException("full audit failed");return;}
        if(!Set.of("prepare","live").contains(mode))throw new IllegalArgumentException("prepare, live or audit required");
        if(mode.equals("live")&&!"true".equals(System.getenv("CLAWKIT_CONTEXT_MEMORY_FULL_EVALUATION")))throw new IllegalStateException("separate live-full human data/budget authorization and opt-in required");
        var input=EvaluationArtifacts.JSON.readTree(Files.readAllBytes(dataset));var spec=LiveFullSpec.parse(input);var artifacts=new EvaluationArtifacts(output);
        artifacts.write("frozen-data.json",input);artifacts.write("ordered-instances.json",spec.instances());var sources=EvaluationSourceSnapshot.freeze(artifacts);
        try(var variants=FrozenContextVariants.prepare(artifacts)) {
            var manifest=new LinkedHashMap<String,Object>();manifest.put("suite",spec.version());manifest.put("split","INDEPENDENT_LIVE_FULL");manifest.put("mode",mode.equals("live")?"live-full":"offline-preparation");
            manifest.put("startedAt",Instant.now());manifest.put("datasetHash",EvaluationArtifacts.sha256(Files.readAllBytes(artifacts.resolve("frozen-data.json"))));manifest.put("sourceHashes",sources);
            manifest.put("settings",spec.settings());manifest.put("limits",spec.limits());manifest.put("model",spec.model());manifest.put("contextVariants",variants.metadata());
            manifest.put("completionAcceptance",Map.of("configured",spec.acceptanceEnabled(),"id",spec.acceptanceEnabled()?LiveFullPublicAcceptance.ID:"NONE",
                "sharedAcrossArms",true,"maxCorrections",spec.acceptanceEnabled()?2:0,"expectedValuesInFeedback",false,"independentScoringUnchanged",true));
            manifest.put("inputReservePlanning",Map.of("enabled",true,"sharedAcrossArms",true,
                "reservePolicy","UNCHANGED_UTF8_DISPATCH_RESERVE","planningCapacityIsActualBalance",false,
                "additionalProviderCalls",0,"providerHardCheckUnchanged",true,"records","input-budget-plans.jsonl"));
            manifest.put("historyPolicy","one natural initial query; no preloaded messages; ordinary engine history from actual model/tool exchanges");
            manifest.put("environmentPolicy","public structural transitions; environment receives AgentInput and EnvironmentProgram only; no outcome gold");
            manifest.put("sharedRuntimePolicy","same Engine raw-context path; selected pipeline owns masking/compaction; C1 retains three complete exchanges and USER");
            manifest.put("effectClaimsAllowed",false);manifest.put("productionEfficiencyMeasured",false);artifacts.write("manifest.json",manifest);
            var preflight=LiveFullPreflight.run(spec,artifacts,variants);String stop=mode.equals("prepare")?"PREPARE_ONLY_NO_PROVIDER":!preflight.ready()?"PREFLIGHT_NOT_READY":null;
            if(stop==null&&!sources.equals(EvaluationSourceSnapshot.hashes(EvaluationSourceSnapshot.repository())))stop="SOURCE_CHANGED_BEFORE_PROVIDER";
            LLMProvider provider=null;if(stop==null){String key=System.getenv("CLAWKIT_API_KEY");if(key==null||key.isBlank())stop="API_KEY_UNAVAILABLE";
                else provider=ProviderFactory.create(LLMConfig.builder().apiKey(key).baseUrl(spec.model().endpoint()).model(spec.model().model()).contextWindow(spec.settings().contextWindow()).encoding(spec.settings().encoding())
                    .maxRetries(0).connectTimeout(Duration.ofSeconds(10)).requestTimeout(Duration.ofSeconds(60)).build());}
            var tokens=BudgetLedger.of(spec.limits().totalTokens());var calls=WorkBudgetLedger.of(spec.limits().totalProviderCalls(),Long.MAX_VALUE);Instant deadline=Instant.now().plusSeconds(spec.limits().roundDeadlineSeconds());var rows=new ArrayList<LiveFullInstance.Row>();
            for(var instance:spec.instances()) {
                if(stop==null&&(!Instant.now().isBefore(deadline)||tokens.remaining()<=0||calls.remainingProviderCalls()<=0))stop="ROUND_DEADLINE_OR_BUDGET_EXHAUSTED";
                LiveFullInstance.Row row;if(stop!=null)row=new LiveFullInstance.Row(instance.id(),instance.taskId(),instance.arm().name(),"NOT_RUN",stop,null,0);
                else try {row=LiveFullInstance.execute(spec,instance,artifacts,provider,tokens,calls,variants,deadline);if(row.evidence()==null||row.evidence().accountingInvalid())stop="ACCOUNTING_OR_EVIDENCE_UNCERTAIN_STOP_ROUND";}
                catch(Exception e){row=new LiveFullInstance.Row(instance.id(),instance.taskId(),instance.arm().name(),"EVALUATION_FAILURE",e.getClass().getSimpleName(),null,0);stop="EVALUATOR_FAILURE_STOP_ROUND";}
                rows.add(row);artifacts.append("instances.jsonl",row);
            }
            artifacts.write("source-integrity.json",Map.of("unchanged",sources.equals(EvaluationSourceSnapshot.hashes(EvaluationSourceSnapshot.repository()))));
            artifacts.write("summary.json",Map.of("plannedInstances",6,"recordedInstances",rows.size(),"attemptedInstances",rows.stream().filter(r->!r.status().equals("NOT_RUN")).count(),
                "notRun",rows.stream().filter(r->r.status().equals("NOT_RUN")).count(),"stopReason",stop==null?"ROUND_COMPLETED":stop,"effectClaimsAllowed",false));artifacts.seal();
            System.out.println("Full task "+mode+": ready="+preflight.ready()+"; outputs="+artifacts.root());if(mode.equals("prepare")&&!preflight.ready())throw new IllegalStateException("full initial boundary preflight failed");
        }
    }
}
