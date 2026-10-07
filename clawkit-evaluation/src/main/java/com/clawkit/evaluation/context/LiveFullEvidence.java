package com.clawkit.evaluation.context;

import com.clawkit.observability.*;
import com.clawkit.provider.UsageSource;
import java.nio.file.*;
import java.util.*;

/** Reads actual files, raw calls and traces anew. Synthetic responses cannot satisfy the live gate. */
final class LiveFullEvidence {
    private record EventFact(long sequence,java.time.Instant occurredAt,String runId,String parentRunId,Integer turn,RunEventPayload payload){
        static EventFact of(RunEventEnvelope event){return new EventFact(event.sequence(),event.occurredAt(),event.runId(),event.parentRunId(),event.turnNumber(),event.payload());}
    }
    record Result(ContinuationTaskScorer.Result outcome,UsageLedger.Totals usage,long dispatchedProviderCalls,
        int actualMainTurns,int pressureCompactions,boolean freshnessSatisfied,boolean accountingInvalid,
        Map<String,UsageLedger.Totals> stages,long rootDurationMs,boolean liveProviderProvenance,boolean requestContract,boolean withinLimits,boolean qualitySatisfied,boolean liveFullGateSatisfied) {}
    static Result recompute(Path root,LiveFullSpec spec,LiveFullSpec.Instance instance) throws Exception {
        var task=spec.task(instance);Path home=root.resolve("agents/"+instance.id()+"/home"), work=root.resolve("agents/"+instance.id()+"/workspace"),directory=root.resolve("instances/"+instance.id());
        var ids=new ArrayList<String>();Path runs=home.resolve(".clawkit/runs");
        if(Files.isDirectory(runs))try(var paths=Files.list(runs)){paths.filter(Files::isDirectory).sorted().forEach(p->ids.add(p.getFileName().toString()));}
        var codec=new RunEventCodec(EvaluationArtifacts.JSON);var events=new ArrayList<RunEventEnvelope>();var sequences=new HashMap<String,Long>();
        for(var line:Files.readAllLines(directory.resolve("runtime-events.jsonl")))if(!line.isBlank()) {
            var event=codec.deserialize(line);if(event.payload() instanceof UnknownEventPayload||event.sequence()!=sequences.merge(event.runId(),1L,Long::sum)||event.schemaVersion()!=RunEventEnvelope.CURRENT_SCHEMA_VERSION)
                throw new IllegalStateException("malformed full event trace");events.add(event);
        }
        var reader=new RunReader(home.resolve(".clawkit"));
        for(var id:ids){var read=reader.readEvents(id);if(!read.warnings().isEmpty())throw new IllegalStateException("malformed lifecycle trace");
            var all=events.stream().filter(e->e.runId().equals(id)).toList();if(all.size()!=read.value().size())throw new IllegalStateException("lifecycle trace count differs");
            if(!all.stream().map(EventFact::of).toList().equals(read.value().stream().map(EventFact::of).toList()))throw new IllegalStateException("lifecycle and all-phase traces differ");
        }
        var roots=events.stream().filter(e->e.payload() instanceof RunStartedPayload&&e.parentRunId()==null).toList();
        if(roots.size()!=1)throw new IllegalStateException("one empty-history root required");String taskRoot=roots.getFirst().runId();
        var tools=ContinuationTraceReader.read(home,directory,ids);
        var score=ContinuationTaskScorer.score(work,task.gold(),task.agentInput().workspaceFiles(),tools,ContinuationTraceReader.taskStatus(home,taskRoot));
        var turnStarts=new HashSet<Integer>();for(var e:events)if(e.runId().equals(taskRoot)&&e.payload() instanceof TurnStartedPayload)if(!turnStarts.add(e.turnNumber()))throw new IllegalStateException("duplicate main TurnStarted");
        var actualTurns=new HashSet<Integer>();var ledger=new ArrayList<UsageLedger.Entry>();boolean reserveViolation=false,contract=true;
        int proposedTools=0;var callBuckets=new HashSet<String>();
        if(Files.isDirectory(directory.resolve("calls")))try(var calls=Files.list(directory.resolve("calls"))){for(var file:calls.filter(p->p.toString().endsWith("-response.json")).sorted().toList()){
            var raw=EvaluationArtifacts.JSON.readTree(file.toFile());var request=EvaluationArtifacts.JSON.readTree(file.resolveSibling(file.getFileName().toString().replace("-response.json","-request.json")).toFile());
            var entry=EvaluationArtifacts.JSON.treeToValue(raw.path("usageEntry"),UsageLedger.Entry.class);ledger.add(entry);
            var parameters=request.path("parameters");contract &= parameters.path("maxTokens").asInt()>0&&parameters.path("maxTokens").asInt()<=spec.limits().outputTokensPerCall()
                &&parameters.path("temperature").isNumber()&&parameters.path("temperature").asDouble()==0&&parameters.path("reasoningMode").asText().equals("DISABLED")&&!parameters.path("stream").asBoolean(true);
            for(var tool:request.path("tools"))contract &= FixedToolEvaluationGateway.TASK_TOOLS.contains(tool.path("name").asText());
            long upper=EvaluationArtifacts.JSON.writeValueAsBytes(Map.of("messages",request.path("messages"),"tools",request.path("tools"))).length+spec.settings().framingReserveTokens()+spec.limits().outputTokensPerCall();
            reserveViolation |= entry.usage().source()==UsageSource.ACTUAL && entry.usage().totalTokens()>upper;
            proposedTools+=raw.path("response").path("toolCalls").size();
            var response=raw.hasNonNull("response")?raw.path("response"):raw.path("rejectedResponse");
            if(!response.isMissingNode())contract &= response.path("usage").equals(raw.path("usageEntry").path("usage"));
            String bucket=request.path("runId").asText()+"/"+request.path("phase").asText()+"/"+request.path("turn").asInt();
            if(entry.usage().source()==UsageSource.ACTUAL) { if(request.path("runId").asText().equals(taskRoot)&&request.path("phase").asText().equals("REACT")&&!callBuckets.add(bucket))throw new IllegalStateException("duplicate received main call scope");
                if(request.path("runId").asText().equals(taskRoot)&&request.path("phase").asText().equals("REACT")&&turnStarts.contains(request.path("turn").asInt()))actualTurns.add(request.path("turn").asInt()); }
        }}
        var usage=UsageLedger.total(ledger);long dispatched=events.stream().filter(e->e.payload() instanceof ProviderCallStartedPayload).count();
        // COMPACT can have multiple calls within one scope (map/reduce); only root main turns must be unique.
        boolean invalid=reserveViolation||dispatched!=usage.actualCalls();
        int pressure=0;Path context=directory.resolve("context");if(Files.isDirectory(context))try(var paths=Files.list(context)){for(var file:paths.filter(p->p.toString().endsWith("-result.json")).toList()){
            var result=EvaluationArtifacts.JSON.readTree(file.toFile());String level=result.path("audit").path("level").asText();
            if(result.path("compacted").asBoolean(false)&&result.path("audit").path("failureCode").isNull()&&Set.of("L2_EXTRACTIVE","L3_GENERATIVE").contains(level)
                &&result.path("beforeReport").path("totalTokens").asLong()>=com.clawkit.context.ContextBudgetPolicy.of(spec.settings().contextWindow()).warningTokens()
                &&result.path("afterReport").path("totalTokens").asLong()<result.path("beforeReport").path("totalTokens").asLong())pressure++;
        }}
        boolean fresh=freshness(root,instance,task,tools,work,taskRoot);
        boolean within=dispatched<=spec.limits().instanceProviderCalls()&&proposedTools<=spec.settings().instanceToolCalls()
            &&(usage.actualTotalTokens()==null||usage.actualTotalTokens()<=spec.limits().instanceTotalTokens());
        boolean liveProvenance=Files.isRegularFile(root.resolve("manifest.json")) && EvaluationArtifacts.JSON.readTree(root.resolve("manifest.json").toFile()).path("mode").asText().equals("live-full")
            && dispatched>0 && events.stream().filter(e->e.payload() instanceof ProviderCallStartedPayload).map(e->(ProviderCallStartedPayload)e.payload()).allMatch(p->"DEEPSEEK_V4".equals(p.providerDialect())&&spec.model().model().equals(p.requestedModel()));
        var stages=new TreeMap<String,UsageLedger.Totals>();ledger.stream().collect(java.util.stream.Collectors.groupingBy(UsageLedger.Entry::phase)).forEach((phase,entries)->stages.put(phase,UsageLedger.total(entries)));
        var completion=events.stream().filter(e->e.runId().equals(taskRoot)&&e.payload() instanceof RunCompletedPayload).map(RunEventEnvelope::occurredAt).findFirst();
        long rootDuration=completion.isPresent()?java.time.Duration.between(roots.getFirst().occurredAt(),completion.get()).toMillis():-1;
        boolean quality=score.taskCompleted()&&fresh;
        return new Result(score,usage,dispatched,liveProvenance?actualTurns.size():0,pressure,fresh,invalid,Map.copyOf(stages),rootDuration,liveProvenance,contract,within,quality,
            liveProvenance&&quality&&actualTurns.size()>=20&&pressure>0&&!invalid&&contract&&within);
    }
    private static boolean freshness(Path root,LiveFullSpec.Instance instance,LiveFullSpec.Task task,List<ContinuationTaskScorer.ToolObservation> tools,Path work,String taskRoot)throws Exception {
        if(!task.environment().kind().equals("CHANGING_EVIDENCE"))return true;
        Path dir=root.resolve("instances/"+instance.id());if(!Files.isRegularFile(dir.resolve("environment-transitions.jsonl"))||!Files.isRegularFile(dir.resolve("environment-reads.jsonl")))return false;
        var transitions=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();for(var line:Files.readAllLines(dir.resolve("environment-transitions.jsonl")))if(!line.isBlank())transitions.add(EvaluationArtifacts.JSON.readTree(line));
        if(transitions.size()!=2)return false;
        for(int i=0;i<2;i++) {
            var transition=transitions.get(i);if(transition.path("from").asInt()!=i+1||transition.path("to").asInt()!=i+2||!transition.path("source").asText().equals("FIXTURE_OBSERVER_NOT_AGENT_ACTION"))return false;
            if(!EvaluationArtifacts.JSON.readTree(transition.path("newStatusBytes").asText()).equals(task.environment().revisions().get(i+1)))return false;
            String trigger=transition.path("triggerCallId").asText(),triggerPath=transition.path("triggerPath").asText();
            if(!taskRoot.equals(transition.path("runId").asText())||tools.stream().noneMatch(t->t.runId().equals(taskRoot)&&t.callId().equals(trigger)&&t.tool().equals("write")&&triggerPath.equals(t.path())&&t.executed()&&!t.failed()))return false;
            if(i==0){String path=transition.path("triggerPath").asText();if(!task.environment().previewFiles().contains(path)||!LiveFullEnvironment.structurallyValid(task.environment().kind(),path,1,EvaluationArtifacts.JSON.readTree(transition.path("previewBytes").path(path).asText())))return false;}
            else for(var path:task.environment().previewFiles())if(!LiveFullEnvironment.structurallyValid(task.environment().kind(),path,2,EvaluationArtifacts.JSON.readTree(transition.path("previewBytes").path(path).asText())))return false;
        }
        if(!EvaluationArtifacts.JSON.readTree(work.resolve(task.environment().statusFile()).toFile()).equals(task.environment().revisions().get(2)))return false;
        var finalTime=java.time.Instant.parse(transitions.get(1).path("occurredAt").asText());
        for(var line:Files.readAllLines(dir.resolve("environment-reads.jsonl")))if(!line.isBlank()) {
            var read=EvaluationArtifacts.JSON.readTree(line);String call=read.path("callId").asText();
            String statusPath=task.environment().statusFile();
            String outputHash=EvaluationArtifacts.sha256(read.path("output").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String finalHash=EvaluationArtifacts.sha256(transitions.get(1).path("newStatusBytes").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(read.path("revision").asInt()==3&&java.time.Instant.parse(read.path("observedAt").asText()).compareTo(finalTime)>=0
                &&taskRoot.equals(read.path("runId").asText())&&statusPath.equals(read.path("path").asText())
                &&tools.stream().anyMatch(t->t.runId().equals(taskRoot)&&t.callId().equals(call)&&t.tool().equals("read")&&statusPath.equals(t.path())&&t.executed()&&!t.failed())
                &&outputHash.equals(read.path("statusFileHash").asText())&&outputHash.equals(finalHash)
                &&EvaluationArtifacts.JSON.readTree(read.path("output").asText()).equals(task.environment().revisions().get(2)))return true;
        }
        return false;
    }
}
