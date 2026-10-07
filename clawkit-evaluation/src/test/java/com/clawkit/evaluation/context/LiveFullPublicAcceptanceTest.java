package com.clawkit.evaluation.context;

import com.clawkit.engine.TaskCompletionCheck;
import com.clawkit.provider.*;
import com.clawkit.reliability.*;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.schema.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Offline validation fixtures only. Golden outputs here do not enter a real model or the application check. */
class LiveFullPublicAcceptanceTest {
    @TempDir Path temp;
    private LiveFullSpec spec() throws Exception {return LiveFullSpec.parse(EvaluationArtifacts.JSON.readTree(EvaluationSourceSnapshot.repository().resolve("benchmarks/context-memory-live-full-acceptance-v2.json").toFile()));}
    private TaskCompletionCheck.Request request(){return new TaskCompletionCheck.Request("unit-check",1,"done",ExecutionControl.none());}
    private Path seed(LiveFullSpec.Task task) throws Exception {
        Path work=temp.resolve(task.id());ContinuationRuntime.seed(work,task.agentInput().workspaceFiles());return work;
    }
    private void correctUnitOutputs(Path work,LiveFullSpec.Task task) throws Exception {
        // Test-only values verify check coverage. The callback receives AgentInput/EnvironmentProgram, never Task.
        var outputs=new TreeMap<>(task.gold().additionalJsonFiles());outputs.put(task.gold().jsonFile(),task.gold().requiredFields());
        for(var output:outputs.entrySet()){Path file=work.resolve(output.getKey());Files.createDirectories(file.getParent());Files.writeString(file,EvaluationArtifacts.JSON.writeValueAsString(output.getValue()));}
    }
    @Test void declaredV2PreparesAllArmsWithoutProviderAndPreservesV1() throws Exception {
        Path dataset=EvaluationSourceSnapshot.repository().resolve("benchmarks/context-memory-live-full-acceptance-v2.json");
        var spec=spec();assertThat(spec.acceptanceEnabled()).isTrue();
        var v1=LiveFullSpec.parse(EvaluationArtifacts.JSON.readTree(EvaluationSourceSnapshot.repository().resolve("benchmarks/context-memory-live-full-v1.json").toFile()));
        assertThat(v1.acceptanceEnabled()).isFalse();assertThat(spec.limits()).isEqualTo(v1.limits());
        for(int i=0;i<2;i++){assertThat(spec.tasks().get(i).gold()).isEqualTo(v1.tasks().get(i).gold());assertThat(spec.tasks().get(i).environment()).isEqualTo(v1.tasks().get(i).environment());}
        var changed=(ObjectNode)EvaluationArtifacts.JSON.readTree(dataset.toFile());changed.remove("completionAcceptance");
        assertThatThrownBy(()->LiveFullSpec.parse(changed)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("declaration");
        Path root=temp.resolve("v2-prepare");ContextMemoryFull.run("prepare",root,dataset);
        var preflight=EvaluationArtifacts.JSON.readTree(root.resolve("preflight.json").toFile());assertThat(preflight.path("ready").asBoolean()).isTrue();assertThat(preflight.path("providerCalls").asInt()).isZero();
        for(var check:preflight.path("checks")){assertThat(check.path("completionCheckConfigured").asBoolean()).isTrue();assertThat(check.path("completionCheckId").asText()).isEqualTo(LiveFullPublicAcceptance.ID);}
        assertThat(preflight.path("checks").size()).isEqualTo(6);
        var audit=LiveFullAudit.recompute(root);assertThat(audit.artifactIntegrity()).isTrue();assertThat(audit.outcomesAgree()).isTrue();assertThat(audit.notRun()).isEqualTo(6);assertThat(audit.liveFullGateSatisfied()).isFalse();
    }
    @Test void migrationChecksAllPublicMappingsWithoutReturningAnswerValues() throws Exception {
        var task=spec().tasks().getFirst();Path work=seed(task);correctUnitOutputs(work,task);
        var check=new LiveFullPublicAcceptance(work,task.agentInput(),task.environment());
        var before=EvaluationArtifacts.hashes(work);assertThat(check.check(request()).decision()).isEqualTo(TaskCompletionCheck.Decision.ACCEPT);assertThat(EvaluationArtifacts.hashes(work)).isEqualTo(before);
        Path file=work.resolve("preview/prod/gateway.json");var json=(ObjectNode)EvaluationArtifacts.JSON.readTree(file.toFile());
        ((ObjectNode)json.path("upstreams").get(0)).put("url","https://accounts.prod.example.test:8101");
        ((ObjectNode)json.path("telemetry")).putNull("traceSamplePercent");json.put("extra",123);Files.writeString(file,json.toString());
        var result=check.check(request());assertThat(result.decision()).isEqualTo(TaskCompletionCheck.Decision.RETRY);
        assertThat(result.feedback()).contains("/upstreams/0/url","/telemetry/traceSamplePercent","EXTRA_FIELD","environments/prod.json").doesNotContain("https://accounts.example.test");
        assertThat(result.feedback().length()).isLessThanOrEqualTo(2048);
    }
    @Test void evidenceMustUseCurrentSnapshotAndOrderedActionRule() throws Exception {
        var task=spec().tasks().get(1);Path work=seed(task);correctUnitOutputs(work,task);
        var check=new LiveFullPublicAcceptance(work,task.agentInput(),task.environment());
        assertThat(check.check(request()).decision()).isEqualTo(TaskCompletionCheck.Decision.RETRY); // final unit outputs do not match revision 1
        Files.writeString(work.resolve(task.environment().statusFile()),task.environment().revisions().get(2).toString());
        assertThat(check.check(request()).decision()).isEqualTo(TaskCompletionCheck.Decision.ACCEPT);
        Path file=work.resolve("preview/decisions/worker-01.json");var json=(ObjectNode)EvaluationArtifacts.JSON.readTree(file.toFile());json.put("action","INVESTIGATE");Files.writeString(file,json.toString());
        var result=check.check(request());assertThat(result.decision()).isEqualTo(TaskCompletionCheck.Decision.RETRY);
        assertThat(result.feedback()).contains("/action","docs/decisions.md").doesNotContain("PROPOSE_AUTHORIZED_RESTART");
    }
    @Test void sourceChangesAndOversizedFilesRejectWithoutWritingWorkspace() throws Exception {
        var task=spec().tasks().getFirst();Path work=seed(task);correctUnitOutputs(work,task);
        var check=new LiveFullPublicAcceptance(work,task.agentInput(),task.environment());
        Files.writeString(work.resolve("environments/prod.json"),"{}");var before=EvaluationArtifacts.hashes(work);
        assertThat(check.check(request()).code()).isEqualTo("PROTECTED_SOURCE_CHANGED");assertThat(EvaluationArtifacts.hashes(work)).isEqualTo(before);
        Files.writeString(work.resolve("environments/prod.json"),task.agentInput().workspaceFiles().get("environments/prod.json"));
        Files.writeString(work.resolve("preview/prod/gateway.json"),"x".repeat(65537));before=EvaluationArtifacts.hashes(work);
        assertThat(check.check(request()).code()).isEqualTo("UNBOUNDED_CHECK_FILE");assertThat(EvaluationArtifacts.hashes(work)).isEqualTo(before);
    }
    @Test void missingAndInvalidPreviewsYieldBoundedLocationsAndWrongRuleDocumentsReject() throws Exception {
        var task=spec().tasks().getFirst();Path work=seed(task);var check=new LiveFullPublicAcceptance(work,task.agentInput(),task.environment());
        var result=check.check(request());var feedback=EvaluationArtifacts.JSON.readTree(result.feedback());assertThat(result.decision()).isEqualTo(TaskCompletionCheck.Decision.RETRY);
        assertThat(feedback.path("issueCount").asInt()).isEqualTo(25);assertThat(feedback.path("issues").size()).isLessThanOrEqualTo(8);assertThat(result.feedback().length()).isLessThanOrEqualTo(2048);
        Files.createDirectories(work.resolve("preview/dev"));Files.writeString(work.resolve("preview/dev/gateway.json"),"not-json");
        assertThat(check.check(request()).feedback()).contains("MISSING_OR_INVALID_JSON");
        var files=new TreeMap<>(task.agentInput().workspaceFiles());files.put("docs/migration.md","some other natural language rules");
        assertThatThrownBy(()->new LiveFullPublicAcceptance(work,new LiveFullSpec.AgentInput(task.agentInput().query(),files),task.environment())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void configuredProductionRunCannotDeclareIncompleteTaskCompleted() throws Exception {
        var spec=spec();var instance=spec.instances().getFirst();var artifacts=new EvaluationArtifacts(temp.resolve("v2-incomplete-run"));EvaluationSourceSnapshot.freeze(artifacts);
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        // Fake usage exercises bounded continuation; UNKNOWN provider provenance keeps actual main turns at zero.
        LLMProvider fixture=new LLMProvider(){public ModelResponse generate(ModelRequest r){requests.incrementAndGet();return ModelResponse.text("already complete",new TokenUsage(10,2,12));}public Message generate(List<Message> m,List<ToolDefinition> t){throw new AssertionError();}};
        try(var variants=FrozenContextVariants.prepare(artifacts)) {
            var row=LiveFullInstance.execute(spec,instance,artifacts,fixture,BudgetLedger.of(spec.limits().totalTokens()),WorkBudgetLedger.of(360,Long.MAX_VALUE),variants,Instant.now().plusSeconds(60));
            var receipts=EvaluationArtifacts.JSON.readTree(artifacts.resolve("instances/"+instance.id()+"/completion-checks.json").toFile());
            assertThat(requests.get()).isEqualTo(3);assertThat(receipts.size()).isEqualTo(3);
            String runId=receipts.get(0).path("runId").asText();
            assertThat(ContinuationTraceReader.taskStatus(artifacts.resolve("agents/"+instance.id()+"/home"),runId)).isEqualTo("UNKNOWN_ERROR");
            assertThat(row.evidence().outcome().taskCompleted()).isFalse();assertThat(row.evidence().actualMainTurns()).isZero();assertThat(row.evidence().liveFullGateSatisfied()).isFalse();
        }
    }
}
