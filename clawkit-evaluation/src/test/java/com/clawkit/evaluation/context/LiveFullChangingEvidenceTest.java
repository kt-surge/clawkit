package com.clawkit.evaluation.context;

import com.clawkit.provider.*;
import com.clawkit.reliability.*;
import com.clawkit.tools.schema.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Native files and engine, fixed responses, no network. This is evaluator coverage, never model-effect evidence. */
class LiveFullChangingEvidenceTest {
    @TempDir Path temp;

    @Test void finalEvidenceMustJoinTheActualRunPathAndBytesAfterBothRevisions() throws Exception {
        var spec=LiveFullSpec.parse(EvaluationArtifacts.JSON.readTree(EvaluationSourceSnapshot.repository().resolve("benchmarks/context-memory-live-full-v1.json").toFile()));
        var instance=spec.instances().stream().filter(i->spec.task(i).environment().kind().equals("CHANGING_EVIDENCE")&&i.arm()==ContinuationSpec.Arm.C3_CANDIDATE_CONTEXT).findFirst().orElseThrow();
        var artifacts=new EvaluationArtifacts(temp.resolve("changing-evidence-fixture-only"));
        EvaluationSourceSnapshot.freeze(artifacts);
        var fixture=new PublicEvidenceFixture();
        try(var variants=FrozenContextVariants.prepare(artifacts)) {
            var row=LiveFullInstance.execute(spec,instance,artifacts,fixture,BudgetLedger.of(spec.limits().totalTokens()),WorkBudgetLedger.of(360,Long.MAX_VALUE),variants,Instant.now().plusSeconds(120));
            assertThat(row.evidence()).as("row=%s; evidence failure=%s",row,Files.exists(artifacts.resolve("instances/"+instance.id()+"/evidence-failure.json"))?Files.readString(artifacts.resolve("instances/"+instance.id()+"/evidence-failure.json")):"none").isNotNull();
            assertThat(row.evidence().qualitySatisfied()).as("evidence=%s",row.evidence()).isTrue();
            assertThat(row.evidence().freshnessSatisfied()).isTrue();
            assertThat(fixture.readRevisions).containsExactly(1,2,3);
            assertThat(row.evidence().actualMainTurns()).isZero();
            assertThat(row.evidence().liveProviderProvenance()).isFalse();
            assertThat(row.evidence().liveFullGateSatisfied()).isFalse();
            assertThat(LiveFullEvidence.recompute(artifacts.root(),spec,instance)).isEqualTo(row.evidence());

            Path reads=artifacts.resolve("instances/"+instance.id()+"/environment-reads.jsonl");
            String original=Files.readString(reads);
            assertTamperRejected(reads,original,"statusFileHash","0".repeat(64),artifacts,spec,instance);
            assertTamperRejected(reads,original,"path","history/completed-actions.json",artifacts,spec,instance);
            assertTamperRejected(reads,original,"runId","another-run",artifacts,spec,instance);
            assertTamperRejected(reads,original,"revision",2,artifacts,spec,instance);
            assertThat(LiveFullEvidence.recompute(artifacts.root(),spec,instance)).isEqualTo(row.evidence());
        }
    }

    private static void assertTamperRejected(Path reads,String original,String field,Object value,EvaluationArtifacts artifacts,LiveFullSpec spec,LiveFullSpec.Instance instance)throws Exception {
        var changed=new ArrayList<String>();
        for(var line:original.lines().toList()) {
            var record=(ObjectNode)EvaluationArtifacts.JSON.readTree(line);
            if(record.path("revision").asInt()==3)record.set(field,EvaluationArtifacts.JSON.valueToTree(value));
            changed.add(record.toString());
        }
        try {
            Files.writeString(reads,String.join("\n",changed)+"\n");
            var evidence=LiveFullEvidence.recompute(artifacts.root(),spec,instance);
            assertThat(evidence.outcome().taskCompleted()).isTrue(); // output correctness alone is insufficient
            assertThat(evidence.freshnessSatisfied()).as("tampered final-read %s",field).isFalse();
            assertThat(evidence.qualitySatisfied()).isFalse();
        } finally {Files.writeString(reads,original);}
    }

    private static final class PublicEvidenceFixture implements LLMProvider {
        private enum Phase { INITIAL,FIRST,READ_R2,WRITE_R2,READ_R3,WRITE_R3,SUMMARY,VALIDATE,FINISH }
        private Phase phase=Phase.INITIAL;
        private final Deque<Map.Entry<String,JsonNode>> pending=new ArrayDeque<>();
        private final List<Integer> readRevisions=new ArrayList<>();
        private int writes;
        @Override public ModelResponse generate(ModelRequest request) {
            // The fictitious usage is only for accounting-contract coverage. Provider provenance remains UNKNOWN.
            var usage=new TokenUsage(10,2,12);
            if(request.tools().isEmpty())return ModelResponse.text("根据实际采证继续生成预览，保留历史完成记录，最终重新读取状态并校验。",usage);
            List<ToolCall> calls;
            switch(phase) {
                case INITIAL -> {phase=Phase.FIRST;calls=List.of(read("rules","docs/decisions.md"),read("history","history/completed-actions.json"),read("read-r1","runtime/live-status.json"));}
                case FIRST -> {var current=status(request,"read-r1");phase=Phase.READ_R2;var target=current.path("targets").get(0);calls=List.of(write("preview/decisions/"+target.path("id").asText()+".json",decision(current,target)));}
                case READ_R2 -> {phase=Phase.WRITE_R2;calls=List.of(read("read-r2","runtime/live-status.json"));}
                case WRITE_R2 -> {if(pending.isEmpty())enqueue(status(request,"read-r2"));var next=pending.removeFirst();calls=List.of(write(next.getKey(),next.getValue()));if(pending.isEmpty())phase=Phase.READ_R3;}
                case READ_R3 -> {phase=Phase.WRITE_R3;calls=List.of(read("read-r3","runtime/live-status.json"));}
                case WRITE_R3 -> {if(pending.isEmpty())enqueue(status(request,"read-r3"));var next=pending.removeFirst();calls=List.of(write(next.getKey(),next.getValue()));if(pending.isEmpty())phase=Phase.SUMMARY;}
                case SUMMARY -> {phase=Phase.VALIDATE;calls=List.of(write("preview/evidence-summary.json",EvaluationArtifacts.JSON.createObjectNode().put("revision",3).put("targetCount",10).put("repairExecuted",false).put("completedActionsReplayed",false)));}
                case VALIDATE -> {phase=Phase.FINISH;calls=List.of(read("validate","runtime/validation.json"));}
                case FINISH -> {return ModelResponse.text("fixture completed",usage);}
                default -> throw new AssertionError();
            }
            return new ModelResponse(null,calls,FinishReason.TOOL_CALLS,usage,ProviderResponseMetadata.EMPTY);
        }
        private JsonNode status(ModelRequest request,String callId) {
            var output=request.messages().stream().filter(m->callId.equals(m.toolCallId())).reduce((a,b)->b).orElseThrow();
            try {var current=EvaluationArtifacts.JSON.readTree(output.content());readRevisions.add(current.path("revision").asInt());return current;}
            catch(Exception e){throw new AssertionError("fixture requires an actual native status read",e);}
        }
        private void enqueue(JsonNode current) {
            for(var target:current.path("targets")) {
                String id=target.path("id").asText();
                pending.add(Map.entry("preview/decisions/"+id+".json",decision(current,target)));
                var facts=target.deepCopy();((ObjectNode)facts).remove("id");
                var evidence=EvaluationArtifacts.JSON.createObjectNode().put("target",id).put("snapshotRevision",current.path("revision").asInt()).put("source","runtime/live-status.json").put("historicalActionsReplayed",false);
                evidence.set("facts",facts);pending.add(Map.entry("preview/evidence/"+id+".json",evidence));
            }
        }
        private static JsonNode decision(JsonNode current,JsonNode target) {
            boolean elapsed=target.path("observedEpoch").asLong()-target.path("lastActionEpoch").asLong()>=target.path("cooldownSeconds").asLong();
            String health=target.path("health").asText(),action;
            if(target.path("maintenance").asBoolean()||!target.path("desiredRunning").asBoolean())action="WAIT";
            else if(health.equals("HEALTHY"))action="NOOP";
            else if(health.equals("UNKNOWN")||!target.path("dependencyHealthy").asBoolean())action="INVESTIGATE";
            else if(health.equals("BUSY"))action="REQUEST_APPROVAL";
            else if(!elapsed)action="WAIT";
            else action=target.path("authorizedRestart").asBoolean()?"PROPOSE_AUTHORIZED_RESTART":"REQUEST_APPROVAL";
            String id=target.path("id").asText();
            var decision=EvaluationArtifacts.JSON.createObjectNode().put("target",id).put("snapshotRevision",current.path("revision").asInt()).put("observedEpoch",target.path("observedEpoch").asLong()).put("serviceVersion",target.path("serviceVersion").asText()).put("action",action).put("repairExecuted",false).put("evidenceFile","preview/evidence/"+id+".json");
            var eligibility=decision.putObject("eligibility");
            for(var field:List.of("maintenance","desiredRunning","dependencyHealthy","authorizedRestart"))eligibility.set(field,target.path(field));
            eligibility.put("cooldownElapsed",elapsed);return decision;
        }
        private static ToolCall read(String id,String path){return new ToolCall(id,"read",EvaluationArtifacts.JSON.createObjectNode().put("path",path));}
        private ToolCall write(String path,JsonNode value){return new ToolCall("write-"+(++writes),"write",EvaluationArtifacts.JSON.createObjectNode().put("path",path).put("content",value.toString()).put("overwrite",true));}
        @Override public Message generate(List<Message> messages,List<ToolDefinition> tools){throw new AssertionError();}
    }
}
