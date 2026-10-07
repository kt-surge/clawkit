package com.clawkit.evaluation.context;

import com.clawkit.context.*;
import com.clawkit.context.impl.*;
import com.clawkit.engine.*;
import com.clawkit.provider.*;
import com.clawkit.reliability.*;
import com.clawkit.tools.*;
import com.clawkit.tools.schema.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** No network. These contracts cannot be reported as actual full-task results. */
class LiveFullContractsTest {
    @TempDir Path temp;
    private JsonNode data()throws Exception {return EvaluationArtifacts.JSON.readTree(EvaluationSourceSnapshot.repository().resolve("benchmarks/context-memory-live-full-v1.json").toFile());}
    private LiveFullSpec spec()throws Exception{return LiveFullSpec.parse(data());}
    @Test void forbidsSeededHistoryAndEnforcesSeparateFullBudget()throws Exception {
        var data=data();var spec=LiveFullSpec.parse(data);assertThat(spec.instances()).hasSize(6);
        assertThat(spec.instances().stream().map(LiveFullSpec.Instance::id).distinct()).hasSize(6);
        ((com.fasterxml.jackson.databind.node.ObjectNode)data.path("tasks").get(0).path("agentInput")).putArray("histories");
        assertThatThrownBy(()->LiveFullSpec.parse(data)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty-history");
        var changed=data();((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("limitsDraft")).put("instanceProviderCalls",61);
        assertThatThrownBy(()->LiveFullSpec.parse(changed)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("budget");
    }
    @Test void structuralProgressionDoesNotUseActionGoldAndProtectedWritesFail()throws Exception {
        var task=spec().tasks().get(1);var artifacts=new EvaluationArtifacts(temp.resolve("fixture-only"));var work=artifacts.resolve("workspace");
        var environment=new LiveFullEnvironment(work,task.agentInput(),task.environment(),artifacts,"observer");var registry=environment.tools();
        var write=registry.lookup("write").orElseThrow();assertThat(write).isNotNull();
        var protectedRequest=request("protected","write","history/completed-actions.json","{}",true);
        assertThat(write.describeAction(protectedRequest)).isNull();assertThat(write.execute(protectedRequest).success()).isFalse();
        assertThat(Files.readString(work.resolve("history/completed-actions.json"))).isEqualTo(task.agentInput().workspaceFiles().get("history/completed-actions.json"));
        String first=task.environment().previewFiles().getFirst();var firstValue=task.gold().additionalJsonFiles().get(first);
        var wrong=EvaluationArtifacts.JSON.valueToTree(firstValue);((com.fasterxml.jackson.databind.node.ObjectNode)wrong).put("snapshotRevision",1).put("action","WRONG_BUT_STRUCTURAL");
        assertThat(write.execute(request("first","write",first,wrong.toString(),false)).success()).isTrue();
        assertThat(EvaluationArtifacts.JSON.readTree(work.resolve(task.environment().statusFile()).toFile()).path("revision").asInt()).isEqualTo(2);
        int call=0;for(var path:task.environment().previewFiles()) {
            var value=EvaluationArtifacts.JSON.valueToTree(task.gold().additionalJsonFiles().get(path));((com.fasterxml.jackson.databind.node.ObjectNode)value).put("snapshotRevision",2);
            if(path.contains("decisions"))((com.fasterxml.jackson.databind.node.ObjectNode)value).put("action","WRONG_BUT_STRUCTURAL");
            assertThat(write.execute(request("r2-"+(++call),"write",path,value.toString(),true)).success()).isTrue();
        }
        assertThat(EvaluationArtifacts.JSON.readTree(work.resolve(task.environment().statusFile()).toFile()).path("revision").asInt()).isEqualTo(3);
        assertThat(Files.readAllLines(artifacts.resolve("observer/environment-transitions.jsonl"))).hasSize(2);
        assertThat(environment.validation()).containsEntry("structureValid",false); // current files are still revision 2
        assertThat(ContinuationTaskScorer.score(work,task.gold(),task.agentInput().workspaceFiles(),List.of(),"COMPLETED").outputValid()).isFalse();
    }
    @Test void rollingBaselineSummarizesOldExchangesAndRetainsOriginalUserAndParallelPairs()throws Exception {
        var tokenizer=TokenizerFactory.create("cl100k_base");var budget=ContextBudgetPolicy.of(4096);var original=Message.user("只生成预览，禁止重写历史完成记录。");
        var messages=new ArrayList<Message>();messages.add(Message.system("rules"));messages.add(original);
        for(int i=0;i<22;i++){
            messages.add(Message.assistantWithTools(List.of(new ToolCall("a"+i,"read",EvaluationArtifacts.JSON.createObjectNode()),new ToolCall("b"+i,"read",EvaluationArtifacts.JSON.createObjectNode()))));
            messages.add(Message.toolResult("a"+i,"long file ".repeat(250)));messages.add(Message.toolResult("b"+i,"ok"));messages.add(Message.assistant("done"));
        }
        var delegate=new DefaultContextPipeline(new LadderedCompactor(m->"summary",tokenizer),new ContextBudgetAnalyzer(tokenizer,budget),tokenizer,budget);
        ProviderGateway gateway=new ProviderGateway(){public ModelResponse generate(ModelRequest request,RunScope scope){assertThat(scope.phase()).isEqualTo(RunPhase.COMPACT);return ModelResponse.text("前面的文件已查看，保留任务约束并继续。",TokenUsage.EMPTY);}public ModelResponse generateStream(ModelRequest request,RunScope scope,StreamObserver observer){throw new AssertionError();}};
        var pipeline=new LiveFullRollingPipeline(delegate,new ContextBudgetAnalyzer(tokenizer,budget),budget,gateway,new RunScope("summary",null,0,RunPhase.COMPACT,ExecutionMode.REACT));
        var result=pipeline.compact(new CompactionRequest(messages,0,0,CompactionHint.GENERAL,1024,32,100000));
        assertThat(result.audit().failureCode()).isNull();assertThat(result.messages()).contains(original);
        assertThat(result.audit().evictedGroups()).isEqualTo(19);assertThat(ContinuationBaselinePipeline.validToolProtocol(result.messages())).isTrue();
        assertThat(result.messages().stream().filter(m->m.role()==Role.TOOL)).hasSize(6);
    }
    @Test void prepareAndDetachedAuditMakeZeroProviderCallsAndCannotSatisfyLiveGate()throws Exception {
        Path root=temp.resolve("prepare-only");ContextMemoryFull.run("prepare",root,EvaluationSourceSnapshot.repository().resolve("benchmarks/context-memory-live-full-v1.json"));
        var preflight=EvaluationArtifacts.JSON.readTree(root.resolve("preflight.json").toFile());assertThat(preflight.path("ready").asBoolean()).isTrue();assertThat(preflight.path("providerCalls").asInt()).isZero();
        var audit=LiveFullAudit.recompute(root);assertThat(audit.artifactIntegrity()).isTrue();assertThat(audit.outcomesAgree()).isTrue();assertThat(audit.notRun()).isEqualTo(6);assertThat(audit.liveFullGateSatisfied()).isFalse();
    }
    @Test void unavailableFixtureUsageDoesNotCountAsActualMainTurns()throws Exception {
        var spec=spec();var instance=spec.instances().getFirst();var artifacts=new EvaluationArtifacts(temp.resolve("unavailable-fixture-only"));EvaluationSourceSnapshot.freeze(artifacts);
        try(var variants=FrozenContextVariants.prepare(artifacts)) {
            LLMProvider fixture=new LLMProvider(){public ModelResponse generate(ModelRequest request){return ModelResponse.text("synthetic fixture only",TokenUsage.EMPTY);}public Message generate(List<Message> m,List<ToolDefinition> t){throw new AssertionError();}};
            var row=LiveFullInstance.execute(spec,instance,artifacts,fixture,BudgetLedger.of(spec.limits().totalTokens()),WorkBudgetLedger.of(360,Long.MAX_VALUE),variants,Instant.now().plusSeconds(60));
            assertThat(row.evidence()).as("row %s; evidence error=%s",row, Files.exists(artifacts.resolve("instances/"+instance.id()+"/evidence-failure.json"))?Files.readString(artifacts.resolve("instances/"+instance.id()+"/evidence-failure.json")):"none").isNotNull();assertThat(row.evidence().actualMainTurns()).isZero();assertThat(row.evidence().accountingInvalid()).isTrue();assertThat(row.evidence().liveFullGateSatisfied()).isFalse();
            assertThat(row.evidence().usage().unavailableCalls()).isEqualTo(1);
        }
    }
    @Test void completeMigrationFixtureExercisesCompactionAndRegradeWithoutClosingTheLiveGate()throws Exception {
        var spec=spec();var instance=spec.instances().getFirst();var task=spec.task(instance);var artifacts=new EvaluationArtifacts(temp.resolve("complete-migration-fixture-only"));
        EvaluationSourceSnapshot.freeze(artifacts);
        try(var variants=FrozenContextVariants.prepare(artifacts)) {
            var next=new java.util.concurrent.atomic.AtomicInteger();var outputs=new ArrayList<>(task.environment().previewFiles());outputs.add(task.gold().jsonFile());
            LLMProvider fixture=new LLMProvider(){
                @Override public ModelResponse generate(ModelRequest request){
                    // Fake usage exercises accounting only, never a real API cost or model effect.
                    var fakeUsage=new TokenUsage(10,2,12);
                    if(request.tools().isEmpty())return ModelResponse.text("已读源文件，继续尚未完成的预览并在最后读取结构校验。",fakeUsage);
                    int step=next.getAndIncrement();var calls=new ArrayList<ToolCall>();
                    if(step==0){for(var path:task.gold().requiredReadTargets())if(!path.equals("runtime/validation.json"))calls.add(new ToolCall("source-"+calls.size(),"read",EvaluationArtifacts.JSON.createObjectNode().put("path",path)));}
                    else if(step<=outputs.size()){
                        String path=outputs.get(step-1);var fields=path.equals(task.gold().jsonFile())?task.gold().requiredFields():task.gold().additionalJsonFiles().get(path);
                        calls.add(new ToolCall("preview-"+step,"write",EvaluationArtifacts.JSON.createObjectNode().put("path",path).put("content",EvaluationArtifacts.JSON.valueToTree(fields).toString())));
                    }else if(step==outputs.size()+1)calls.add(new ToolCall("validate","read",EvaluationArtifacts.JSON.createObjectNode().put("path","runtime/validation.json")));
                    else return ModelResponse.text("fixture completed",fakeUsage);
                    return new ModelResponse(null,calls,FinishReason.TOOL_CALLS,fakeUsage,ProviderResponseMetadata.EMPTY);
                }
                @Override public Message generate(List<Message> messages,List<ToolDefinition> tools){throw new AssertionError();}
            };
            var row=LiveFullInstance.execute(spec,instance,artifacts,fixture,BudgetLedger.of(spec.limits().totalTokens()),WorkBudgetLedger.of(360,Long.MAX_VALUE),variants,Instant.now().plusSeconds(60));
            assertThat(row.evidence()).as("fixture row=%s; error=%s",row,Files.exists(artifacts.resolve("instances/"+instance.id()+"/evidence-failure.json"))?Files.readString(artifacts.resolve("instances/"+instance.id()+"/evidence-failure.json")):"none").isNotNull();
            assertThat(row.evidence().qualitySatisfied()).isTrue();var compactDir=artifacts.resolve("instances/"+instance.id()+"/context");var compactReports=new ArrayList<JsonNode>();try(var files=Files.list(compactDir)){for(var file:files.filter(p->p.toString().endsWith("-result.json")).toList()){var report=EvaluationArtifacts.JSON.readTree(file.toFile());if(!report.path("audit").path("level").asText().equals("L0_NONE"))compactReports.add(EvaluationArtifacts.JSON.createObjectNode().set("report",report));}}
            assertThat(row.evidence().pressureCompactions()).as("evidence=%s; compactReports=%s",row.evidence(),compactReports.stream().map(n->Map.of("before",n.path("report").path("beforeReport"),"after",n.path("report").path("afterReport"),"audit",n.path("report").path("audit"))).toList()).isPositive();
            assertThat(row.evidence().actualMainTurns()).isZero();assertThat(row.evidence().liveProviderProvenance()).isFalse();assertThat(row.evidence().liveFullGateSatisfied()).isFalse();
            assertThat(LiveFullEvidence.recompute(artifacts.root(),spec,instance)).isEqualTo(row.evidence());
        }
    }
    private ToolExecutionRequest request(String id,String tool,String path,String content,boolean overwrite){return new ToolExecutionRequest(id,tool,EvaluationArtifacts.JSON.createObjectNode().put("path",path).put("content",content).put("overwrite",overwrite),Instant.now());}
}
