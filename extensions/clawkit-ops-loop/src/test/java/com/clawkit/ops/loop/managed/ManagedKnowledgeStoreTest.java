package com.clawkit.ops.loop.managed;

import com.clawkit.observability.CompositeRunRecorder;
import com.clawkit.provider.*;
import com.clawkit.tools.schema.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;
import static com.clawkit.ops.loop.managed.OpsKnowledge.*;
import static org.assertj.core.api.Assertions.*;

class ManagedKnowledgeStoreTest {
    @TempDir Path root;
    static final Clock CLOCK=ManagedDecisionTest.CLOCK;
    static final Instant NOW=ManagedDecisionTest.NOW;

    @Test void immutableDraftNeedsPositiveAndNegativeReplayAndExactScopeBeforeRetrieval() throws Exception {
        var app=ManagedDecisionTest.app(); var store=new ManagedKnowledgeStore(root,CLOCK); var book=book(app,List.of());
        var ref=store.importRunbook(app,book); store.importRunbook(app,book);
        assertThat(store.search(app,"stopped service",3,evidence(app,false,Status.HEALTHY))).isEqualTo(SearchResult.empty());
        assertThatThrownBy(() -> store.importRunbook(app,new RunbookVersion(book.id(),book.version(),book.scope(),"different",book.symptoms(),book.conditions(),
            book.applicability(),book.prohibitions(),book.probes(),book.disposition(),book.playbook(),book.verification(),book.sourceCaseIds(),NOW))).hasMessageContaining("immutable");
        var positive=evidence(app,false,Status.HEALTHY); var negative=evidence(app,true,Status.HEALTHY);
        var onlyPositive=store.replay(ref,new ManagedKnowledgeStore.ReplayInput("all-positive",List.of(
            new ReplaySample("one",app,NOW,positive,true),new ReplaySample("two",app,NOW,positive,true))));
        assertThat(onlyPositive.qualified()).isFalse();
        assertThatThrownBy(() -> store.review(ref,onlyPositive.id(),"human","review")).hasMessageContaining("positive/negative");
        var report=store.replay(ref,new ManagedKnowledgeStore.ReplayInput("scoped-v1",List.of(
            new ReplaySample("stopped",app,NOW,positive,true),new ReplaySample("oom",app,NOW,negative,false),
            new ReplaySample("dependency",app,NOW,evidence(app,false,Status.UNHEALTHY),false),
            new ReplaySample("expired",app,NOW.plusSeconds(91),positive,false),
            new ReplaySample("wrong-environment",copy(app,"other",1),NOW,positive,false),
            new ReplaySample("wrong-version",copy(app,app.composeProject(),2),NOW,positive,false))));
        assertThat(report.results()).hasSize(6).allMatch(ReplayResult::passed);
        store.review(ref,report.id(),"human","fixed positive and danger negatives reviewed");
        assertThat(store.search(app,"STOPPED service",3,positive).runbooks()).singleElement().satisfies(m -> {
            assertThat(m.reference()).isEqualTo(ref); assertThat(m.applicable()).isTrue(); assertThat(m.matchReason()).contains("environment/service/applicationVersion"); });
        assertThat(store.search(copy(app,"other",1),"stopped",3,positive).runbooks()).isEmpty();
        assertThat(store.search(copy(app,app.composeProject(),2),"stopped",3,positive).runbooks()).isEmpty();
        assertThat(store.search(app,"stopped",3,negative).runbooks()).allMatch(m -> !m.applicable());
        var proposal=new OpsDecision(OpsDecision.Disposition.PROPOSE_ACTION,"same playbook is still inapplicable after OOM",
            negative.stream().map(DecisionEvidence::id).toList(),book.playbook(),List.of(),null);
        assertThatThrownBy(() -> store.validateProposal(app,List.of(ref),proposal,negative)).hasMessageContaining("current evidence conditions");
        assertThat(ActionPolicy.ask(app,NOW.plusSeconds(60)).permitsAuto(app,book.playbook(),NOW)).isFalse();
    }
    @Test void revokedHashChangedAndReplayChangedKnowledgeCannotRemainActiveAndGuardPreventsRacingRevocation() throws Exception {
        var app=ManagedDecisionTest.app(); var store=ready(app,root); var ref=store.reference("start-guidance",1);
        try(var lease=store.guard(app,List.of(ref))) {
            assertThatThrownBy(() -> store.revoke(ref,"human","revoke while executing")).isInstanceOf(java.io.IOException.class);
        }
        store.revoke(ref,"human","incorrect guidance");
        assertThat(store.search(app,"stopped",3,evidence(app,false,Status.HEALTHY)).runbooks()).isEmpty();
        assertThatThrownBy(() -> store.validate(app,List.of(ref))).hasMessageContaining("not currently reviewed");
        var review=store.runbooks().getFirst().review();
        assertThatThrownBy(() -> store.review(ref,review.replayId(),"human","re-enable")).hasMessageContaining("cannot be reactivated");
        var separate=ready(app,root.resolve("hash-change")); var other=separate.reference("start-guidance",1);
        Path body=root.resolve("hash-change/runbook-start-guidance-v1.json");
        Files.writeString(body,Files.readString(body).replace("Start stopped service","Modified stopped service"));
        assertThatThrownBy(() -> separate.validate(app,List.of(other))).hasMessageContaining("hash/version changed");
        var replayStore=ready(app,root.resolve("replay-change")); var replayRef=replayStore.reference("start-guidance",1);
        String replayId=replayStore.runbooks().getFirst().review().replayId(); Path samples=root.resolve("replay-change/samples-"+replayId+".json");
        Files.writeString(samples,Files.readString(samples).replace("qualification-v1","changed-v1"));
        assertThatThrownBy(() -> replayStore.validate(app,List.of(replayRef))).hasMessageContaining("replay input changed");
    }
    @Test void caseBeginsAsUnconfirmedDraftAndRevokingItInvalidatesDerivedRunbook() throws Exception {
        var app=ManagedDecisionTest.app(); var store=new ManagedKnowledgeStore(root,CLOCK); var facts=evidence(app,false,Status.HEALTHY);
        var incident=new ManagedIncident("inc-"+UUID.randomUUID(),app.id(),ManagedContracts.hash(app),ManagedContracts.target(app),ManagedIncident.State.HANDOFF,
            NOW,NOW,1,0,null,NOW.plusSeconds(60),ManagedContracts.snapshot(facts),facts,null,List.of(),null,null,"Stopped service needs human investigation");
        var draft=store.archive(app,incident,null,"a".repeat(64),"b".repeat(64));
        assertThat(draft.outcome()).isEqualTo(OutcomeKind.HUMAN_HANDOFF); assertThat(draft.proposedDiagnosis()).isNull();
        assertThat(store.cases()).singleElement().satisfies(c -> { assertThat(c.state()).isEqualTo(State.DRAFT); assertThat(c.review()).isNull(); });
        assertThat(store.search(app,"stopped",3,facts).cases()).isEmpty();
        assertThatThrownBy(() -> store.importRunbook(app,book(app,List.of(draft.id())))).isInstanceOf(java.io.IOException.class);
        store.reviewCase(draft.id(),DiagnosticReport.Cause.APPLICATION_FAILURE,false,"human","independent investigation confirmed cause");
        var ref=store.importRunbook(app,book(app,List.of(draft.id())));
        qualify(store,app,ref);
        assertThat(store.search(app,"stopped",3,facts).cases()).hasSize(1);
        store.reviewCase(draft.id(),null,true,"human","root cause disproven");
        assertThatThrownBy(() -> store.validate(app,List.of(ref))).hasMessageContaining("source case");
        assertThat(store.search(app,"stopped",3,facts).runbooks()).isEmpty();
        assertThat(store.cases().getFirst().opsCase()).isEqualTo(draft);
    }
    @Test void realRuntimeUsesSameBudgetAndScopedKnowledgeAvailabilityAndRecordsMatchedVersions() throws Exception {
        var app=ManagedDecisionTest.app(); var store=ready(app,root.resolve("knowledge")); var facts=evidence(app,false,Status.HEALTHY);
        for(boolean enabled:List.of(true,false)) {
            var count=new AtomicInteger(); LLMProvider provider=new LLMProvider() {
                public Message generate(List<Message> m,List<ToolDefinition> t) { throw new AssertionError("typed request required"); }
                public ModelResponse generate(ModelRequest request) {
                    int turn=count.getAndIncrement(); String name; com.fasterxml.jackson.databind.JsonNode args;
                    if(turn<2) {
                        assertThat(request.parameters()).isEqualTo(OpsDecisionAgent.ModelSettings.multisourceDefaults().parameters());
                        if(turn==0 && enabled) assertThat(request.tools()).anyMatch(t -> t.name().endsWith("search_knowledge"));
                        else assertThat(request.tools()).noneMatch(t -> t.name().endsWith("search_knowledge"));
                    } else {
                        assertThat(request.parameters()).isEqualTo(new ModelParameters(0.0,1024,false,ProviderReasoningMode.DISABLED));
                        assertThat(request.tools()).extracting(ToolDefinition::name).containsExactly("mcp__remote_managed_ops__submit_decision");
                    }
                    if(turn==0) { name="search_knowledge"; args=ManagedContracts.JSON.createObjectNode().put("query","stopped service"); }
                    else if(turn==1) {
                        assertThat(request.messages().stream().filter(m -> m.role()==Role.TOOL).map(Message::content).reduce("",String::concat))
                            .contains(enabled ? "start-guidance" : "no scoped reviewed knowledge exists");
                        name="submit_diagnosis"; args=ManagedContracts.JSON.valueToTree(new DiagnosticReport("Stopped service; root cause unknown",List.of(
                            new DiagnosticReport.Hypothesis("H1",DiagnosticReport.Cause.UNKNOWN,DiagnosticReport.Assessment.UNKNOWN,"Need crash cause",
                                List.of(facts.getFirst().id()),List.of(),List.of("exit cause"),List.of(),List.of()))));
                    } else { name="submit_decision"; args=ManagedContracts.JSON.valueToTree(new OpsDecision(OpsDecision.Disposition.PROPOSE_ACTION,"Scoped stopped service proposal; knowledge is advisory",
                        facts.stream().map(DecisionEvidence::id).toList(),OpsDecision.Playbook.START_STOPPED_V1,List.of(),null)); }
                    return new ModelResponse("",List.of(new ToolCall("c"+turn,"mcp__remote_managed_ops__"+name,args)),FinishReason.TOOL_CALLS,new TokenUsage(100,50,150),ProviderResponseMetadata.EMPTY);
                }
            };
            var outcome=new OpsDecisionAgent(provider,root.resolve("agent-"+enabled),new CompositeRunRecorder(),CLOCK,OpsDecisionAgent.Limits.defaults(),
                OpsDecisionAgent.Guidance.REVIEWED_PLAYBOOKS,OpsDecisionAgent.ModelSettings.multisourceDefaults(),OpsDecisionAgent.Profile.MULTISOURCE,
                enabled ? store : KnowledgeAccess.none()).decide(app,ManagedDecisionTest.observer(Status.STOPPED),com.clawkit.tools.control.ExecutionControl.none(),
                    new OpsDecisionAgent.DecisionContext("inc-test",1,null,facts));
            assertThat(outcome.origin()).isEqualTo(OpsDecisionAgent.Origin.MODEL); assertThat(outcome.failureType()).isNull();
            assertThat(count).hasValue(3); assertThat(outcome.knowledgeReferences()).hasSize(enabled ? 1 : 0);
        }
    }
    static ManagedKnowledgeStore ready(ManagedApplication app,Path path) throws Exception {
        var store=new ManagedKnowledgeStore(path,CLOCK); var ref=store.importRunbook(app,book(app,List.of())); qualify(store,app,ref); return store;
    }
    static void qualify(ManagedKnowledgeStore store,ManagedApplication app,Reference ref) throws Exception {
        var report=store.replay(ref,new ManagedKnowledgeStore.ReplayInput("qualification-v1",List.of(
            new ReplaySample("positive",app,NOW,evidence(app,false,Status.HEALTHY),true),new ReplaySample("oom-negative",app,NOW,evidence(app,true,Status.HEALTHY),false))));
        store.review(ref,report.id(),"human","positive and negative scope checked");
    }
    static RunbookVersion book(ManagedApplication app,List<String> sources) {
        return new RunbookVersion("start-guidance",1,Scope.of(app),"Start stopped service","stopped service exited unhealthy business",
            new Conditions(List.of(new ProbeCondition(Probe.SERVICE,Status.STOPPED),new ProbeCondition(Probe.HEALTH,Status.UNHEALTHY),
                new ProbeCondition(Probe.BUSINESS,Status.UNHEALTHY),new ProbeCondition(Probe.DEPENDENCIES,Status.HEALTHY)),List.of(Probe.RESOURCES),false,true),
            List.of("Known stateless exited service"),List.of("No OOM, missing resources, truncated evidence or failed dependency"),List.of(Probe.RESOURCES),
            OpsDecision.Disposition.PROPOSE_ACTION,OpsDecision.Playbook.START_STOPPED_V1,List.of("Independent sustained business check"),sources,NOW);
    }
    static List<DecisionEvidence> evidence(ManagedApplication app,boolean oom,Status dependencies) throws Exception {
        var ledger=new DecisionEvidenceLedger(app,CLOCK);
        for(Probe p:List.of(Probe.SERVICE,Probe.HEALTH,Probe.BUSINESS,Probe.DEPENDENCIES,Probe.RESOURCES)) ledger.collect((a,probe) -> {
            var payload=EvidenceEnvelope.Payload.snapshot(EvidenceEnvelope.Source.DOCKER_INSPECT,EvidenceEnvelope.Quality.COMPLETE,NOW,"fixed fixture");
            if(probe==Probe.RESOURCES) payload=new EvidenceEnvelope.Payload(payload.collection(),new EvidenceEnvelope.ResourceFacts(oom,oom ? 137 : 0,NOW,null,67108864L),List.of(),List.of());
            return new Observation(a.targetId(),a.composeProject(),a.service(),probe,NOW,switch(probe) { case SERVICE -> Status.STOPPED;
                case DEPENDENCIES -> dependencies; case RESOURCES -> Status.UNKNOWN; default -> Status.UNHEALTHY; },"fixed normalized fixture",payload);
        },p);
        return ledger.snapshot();
    }
    static ManagedApplication copy(ManagedApplication a,String environment,long version) {
        return new ManagedApplication(a.id(),a.targetId(),environment,a.service(),version,a.stateless(),a.desiredState(),a.maintenanceUntil(),a.healthUri(),a.businessUri(),a.businessMarker(),a.checkInterval(),a.evidenceTtl());
    }
}
