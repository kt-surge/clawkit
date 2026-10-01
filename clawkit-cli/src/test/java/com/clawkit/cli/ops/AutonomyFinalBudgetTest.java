package com.clawkit.cli.ops;

import com.clawkit.observability.CompositeRunRecorder;
import com.clawkit.ops.loop.managed.*;
import com.clawkit.provider.*;
import com.clawkit.tools.schema.*;
import java.nio.file.Path;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Synthetic usage deliberately leaves a small final budget; no paid model or measured accuracy. */
class AutonomyFinalBudgetTest {
    @TempDir Path root;
    static final String PREFIX="mcp__remote_managed_ops__";
    RemoteObservationBinding binding() {
        return new RemoteObservationBinding("remote-test","remote-fixture","order-api",AutonomyRemoteProductTest.CONTAINER,
            new RemoteObservationBinding.Endpoint("orders-health",URI.create("http://127.0.0.1:18080/health"),""),
            new RemoteObservationBinding.Endpoint("orders-business",URI.create("http://127.0.0.1:18080/orders"),"accepted"),null,List.of());
    }
    OpsDecisionAgent agent(LLMProvider provider) {
        return agent(provider,KnowledgeAccess.none());
    }
    OpsDecisionAgent agent(LLMProvider provider,KnowledgeAccess knowledge) {
        return new OpsDecisionAgent(provider,root,new CompositeRunRecorder(),Clock.fixed(AutonomyRemoteProductTest.NOW,ZoneOffset.UTC),
            OpsDecisionAgent.Limits.defaults(),OpsDecisionAgent.Guidance.REVIEWED_PLAYBOOKS,OpsDecisionAgent.ModelSettings.multisourceDefaults(),OpsDecisionAgent.Profile.MULTISOURCE,knowledge);
    }
    @Test void acceptedDiagnosisCanFinishWithinRemainingBudgetWithoutDroppingEvidence() throws Exception {
        var script=new AutonomyDiagnosisProductTest.ScriptedDiagnosis("truncated"); var requests=new ArrayList<ModelRequest>();
        LLMProvider provider=new LLMProvider() {
            public ModelResponse generate(ModelRequest request) {
                requests.add(request); var response=script.generate(request); int step=requests.size();
                return new ModelResponse(response.content(),response.toolCalls(),response.finishReason(),
                    step==1 ? new TokenUsage(9990,10,10000) : step==2 ? new TokenUsage(13490,10,13500) : response.usage(),response.metadata());
            }
            public Message generate(List<Message> messages,List<ToolDefinition> tools) { throw new AssertionError("typed provider required"); }
        };
        try(var observer=new RemoteManagedObserver(binding(),new AutonomyRemoteProductTest.ReadSession(),Clock.fixed(AutonomyRemoteProductTest.NOW,ZoneOffset.UTC),null)) {
            var outcome=agent(provider).decide(binding().application("orders",1,Duration.ofSeconds(5)),observer);
            assertThat(outcome.failureType()).isNull(); assertThat(outcome.origin()).isEqualTo(OpsDecisionAgent.Origin.MODEL);
            assertThat(requests).hasSize(3); var last=requests.getLast();
            assertThat(last.tools()).extracting(ToolDefinition::name).containsExactly(PREFIX+"submit_decision");
            assertThat(last.parameters().maxTokens()).isEqualTo(1024);
            assertThat(last.messages()).anyMatch(m -> m.role()==Role.TOOL && m.content().contains("TRUNCATED"));
            long chars=last.messages().stream().mapToLong(m -> m.content()==null ? 0 : m.content().length()).sum();
            long oldEstimate=(chars+requests.getFirst().tools().stream().mapToLong(t -> t.toString().length()).sum()+3)/4+4096;
            assertThat(oldEstimate).isGreaterThan(6500); // Full investigation schemas/output could not fit the same final allowance.
        }
    }
    @Test void rejectedFinalReferenceReopensInvestigationAndKeepsRejectionRecord() throws Exception {
        var script=new AutonomyDiagnosisProductTest.ScriptedDiagnosis("truncated"); var requests=new ArrayList<ModelRequest>();
        LLMProvider provider=new LLMProvider() {
            public ModelResponse generate(ModelRequest request) {
                requests.add(request); var response=script.generate(request);
                if(requests.size()==3) ((com.fasterxml.jackson.databind.node.ObjectNode)response.toolCalls().getFirst().arguments()).putArray("evidenceRefs").add("ev-fabricated");
                return response;
            }
            public Message generate(List<Message> messages,List<ToolDefinition> tools) { throw new AssertionError("typed provider required"); }
        };
        try(var observer=new RemoteManagedObserver(binding(),new AutonomyRemoteProductTest.ReadSession(),Clock.fixed(AutonomyRemoteProductTest.NOW,ZoneOffset.UTC),null)) {
            var outcome=agent(provider).decide(binding().application("orders",1,Duration.ofSeconds(5)),observer);
            assertThat(outcome.failureType()).isNull(); assertThat(requests).hasSize(4);
            assertThat(requests.get(2).tools()).hasSize(1);
            assertThat(requests.get(3).tools()).anyMatch(t -> t.name().equals(PREFIX+"read_service"));
            assertThat(outcome.rejectedSubmissions()).anyMatch(s -> s.contains("unknown evidence reference"));
        }
    }
    @Test void unchangedEvidenceCannotSpendAnotherKnowledgeLookupBeforeDiagnosis() throws Exception {
        var script=new AutonomyDiagnosisProductTest.ScriptedDiagnosis("truncated"); var requests=new ArrayList<ModelRequest>();
        LLMProvider provider=new LLMProvider() {
            public ModelResponse generate(ModelRequest request) {
                requests.add(request); int turn=requests.size();
                if(turn==2 || turn==3) {
                    if(turn==2) assertThat(request.tools()).anyMatch(t -> t.name().equals(PREFIX+"search_knowledge"));
                    else assertThat(request.tools()).noneMatch(t -> t.name().equals(PREFIX+"search_knowledge"));
                    // Deliberate call to an omitted schema is still checked by the trusted tool body.
                    return new ModelResponse(null,List.of(new ToolCall("query-"+turn,PREFIX+"search_knowledge",
                        AutonomyRemoteProductTest.JSON.createObjectNode().put("query","partial request failures query-"+turn))),FinishReason.TOOL_CALLS,
                        new TokenUsage(100,50,150),ProviderResponseMetadata.EMPTY);
                }
                return script.generate(request);
            }
            public Message generate(List<Message> messages,List<ToolDefinition> tools) { throw new AssertionError("typed provider required"); }
        };
        try(var observer=new RemoteManagedObserver(binding(),new AutonomyRemoteProductTest.ReadSession(),Clock.fixed(AutonomyRemoteProductTest.NOW,ZoneOffset.UTC),null)) {
            KnowledgeAccess available=new KnowledgeAccess() {
                public OpsKnowledge.SearchResult search(ManagedApplication a,String q,int k,List<DecisionEvidence> e) { return OpsKnowledge.SearchResult.empty(); }
                public void validate(ManagedApplication a,List<OpsKnowledge.Reference> refs) {}
                public AutoCloseable guard(ManagedApplication a,List<OpsKnowledge.Reference> refs) { return () -> {}; }
            };
            var outcome=agent(provider,available).decide(binding().application("orders",1,Duration.ofSeconds(5)),observer);
            assertThat(outcome.failureType()).isNull(); assertThat(requests).hasSize(5);
            assertThat(requests.get(3).messages()).anyMatch(m -> m.role()==Role.TOOL && m.content().contains("evidence unchanged"));
            assertThat(requests.getLast().tools()).hasSize(1);
        }
    }
    @Test void missingSourcesAndIncompleteHypothesisStayRejectedButCorrectionCanCompleteWithoutEmptyKnowledgeLookup() throws Exception {
        var requests=new ArrayList<ModelRequest>(); var facts=new HashMap<String,com.fasterxml.jackson.databind.JsonNode>();
        var source=new RemoteObservationBinding("remote-test","remote-fixture","order-api",AutonomyRemoteProductTest.CONTAINER,null,null,null,List.of());
        LLMProvider provider=new LLMProvider() {
            public ModelResponse generate(ModelRequest request) {
                requests.add(request); int turn=requests.size();
                assertThat(request.tools()).noneMatch(t -> t.name().equals(PREFIX+"search_knowledge"));
                for(var message:request.messages()) if(message.role()==Role.TOOL && message.content().stripLeading().startsWith("{")) {
                    try { var fact=AutonomyRemoteProductTest.JSON.readTree(message.content());
                        if(fact!=null && fact.has("observation")) facts.put(fact.path("observation").path("probe").asText(),fact);
                    } catch(java.io.IOException e) { throw new IllegalStateException(e); }
                }
                List<ToolCall> calls;
                if(turn==1) {
                    calls=new ArrayList<>(List.of("service","health","business").stream()
                        .map(p -> new ToolCall("read-"+p,PREFIX+"read_"+p,AutonomyRemoteProductTest.JSON.createObjectNode())).toList());
                    calls.add(new ToolCall("hidden-empty-search",PREFIX+"search_knowledge",AutonomyRemoteProductTest.JSON.createObjectNode().put("query","unknown health")));
                } else if(turn<5) {
                    assertThat(facts.get("HEALTH").path("currentAtRequest").asBoolean()).isTrue();
                    assertThat(facts.get("HEALTH").path("diagnosticReferenceEligible").asBoolean()).isFalse();
                    assertThat(facts.get("SERVICE").path("diagnosticReferenceEligible").asBoolean()).isTrue();
                    assertThat(request.messages()).anyMatch(m -> m.role()==Role.TOOL && m.content().contains("no scoped reviewed knowledge exists"));
                    var report=AutonomyRemoteProductTest.JSON.createObjectNode().put("summary","Container is running; business health is unknown.");
                    var h=report.putArray("hypotheses").addObject().put("id","H1").put("cause","UNKNOWN").put("assessment","UNKNOWN")
                        .put("explanation","Unregistered probes cannot establish an application failure.");
                    h.putArray("supportRefs").add(facts.get(turn==2 ? "HEALTH" : "SERVICE").path("id").asText());
                    h.putArray("counterRefs"); h.putArray("missingEvidence").add("Health and business probes are not registered");
                    if(turn!=3) h.putArray("alternativeIds"); h.putArray("nextProbes");
                    if(turn==4) assertThat(request.messages()).anyMatch(m -> m.role()==Role.TOOL && m.content().contains("alternativeIds,nextProbes; include []"));
                    calls=List.of(new ToolCall("diagnosis-"+turn,PREFIX+"submit_diagnosis",report));
                } else {
                    assertThat(request.tools()).hasSize(1);
                    var decision=AutonomyRemoteProductTest.JSON.createObjectNode().put("disposition","ESCALATE").put("reason","Register independent probes before diagnosing business health");
                    decision.putArray("evidenceRefs").add(facts.get("SERVICE").path("id").asText());
                    decision.putNull("playbook"); decision.putArray("nextProbes"); decision.putNull("recheckAfterSeconds");
                    calls=List.of(new ToolCall("decision",PREFIX+"submit_decision",decision));
                }
                return new ModelResponse(null,calls,FinishReason.TOOL_CALLS,new TokenUsage(100,50,150),ProviderResponseMetadata.EMPTY);
            }
            public Message generate(List<Message> messages,List<ToolDefinition> tools) { throw new AssertionError("typed provider required"); }
        };
        try(var observer=new RemoteManagedObserver(source,new AutonomyRemoteProductTest.ReadSession(),Clock.fixed(AutonomyRemoteProductTest.NOW,ZoneOffset.UTC),null)) {
            var outcome=agent(provider).decide(source.application("orders",1,Duration.ofSeconds(5)),observer);
            assertThat(outcome.failureType()).as("runtime=%s",outcome.runtimeResponse()).isNull();
            assertThat(outcome.origin()).isEqualTo(OpsDecisionAgent.Origin.MODEL);
            assertThat(outcome.rejectedSubmissions()).hasSize(2); assertThat(outcome.diagnosis()).isNotNull();
            assertThat(requests).hasSize(5); assertThat(outcome.decision().disposition()).isEqualTo(OpsDecision.Disposition.ESCALATE);
        }
    }
}
