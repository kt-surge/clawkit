package com.clawkit.ops.loop.managed;

import com.clawkit.observability.CompositeRunRecorder;
import com.clawkit.provider.*;
import com.clawkit.tools.schema.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;

class ManagedDecisionTest {
    static final Instant NOW = Instant.parse("2026-09-30T01:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    static ManagedApplication app() {
        return new ManagedApplication("demo", "local-isolated", "clawkit-autonomy-test", "demo-api", 1, true,
            ManagedApplication.DesiredState.RUNNING, null, URI.create("http://127.0.0.1:18080/health"),
            URI.create("http://127.0.0.1:18080/"), "clawkit", Duration.ofSeconds(5), Duration.ofSeconds(90));
    }

    static ManagedObserver observer(Status serviceStatus) {
        return (app, probe) -> new Observation(app.targetId(), app.composeProject(), app.service(), probe, NOW,
            switch (probe) {
                case SERVICE -> serviceStatus;
                case DEPENDENCIES -> Status.HEALTHY;
                case LOGS -> Status.UNKNOWN;
                default -> Status.UNHEALTHY;
            }, "normalized test double; no external probes");
    }

    @ParameterizedTest @EnumSource(OpsDecision.Disposition.class)
    void realRuntimeAcceptsFourEvidenceBoundBranches(OpsDecision.Disposition disposition) {
        var provider = new ScriptedProvider(disposition, false, false);
        var outcome = agent(provider, OpsDecisionAgent.Limits.defaults()).decide(app(), observer(Status.STOPPED));
        assertThat(outcome.origin()).isEqualTo(OpsDecisionAgent.Origin.MODEL);
        assertThat(outcome.decision().disposition()).isEqualTo(disposition);
        assertThat(outcome.failureType()).isNull();
        assertThat(outcome.providerExchanges()).hasSize(2);
        assertThat(provider.calls.get()).isEqualTo(2); // terminal tool avoids an extra model call
        assertThat(outcome.evidence()).hasSize(4);
        assertThat(outcome.rejectedSubmissions()).isEmpty();
        assertThat(outcome.providerExchanges().getFirst().request().tools())
            .allMatch(tool -> tool.name().startsWith("mcp__remote_managed_ops__"))
            .noneMatch(tool -> tool.name().contains("bash") || tool.name().contains("restart_service"));
    }

    @Test void fabricatedReferenceIsRejectedThenModelMayEscalate() {
        var outcome = agent(new ScriptedProvider(OpsDecision.Disposition.ESCALATE, true, false),
            OpsDecisionAgent.Limits.defaults()).decide(app(), observer(Status.STOPPED));
        assertThat(outcome.origin()).isEqualTo(OpsDecisionAgent.Origin.MODEL);
        assertThat(outcome.decision().disposition()).isEqualTo(OpsDecision.Disposition.ESCALATE);
        assertThat(outcome.rejectedSubmissions()).anyMatch(s -> s.contains("unknown evidence reference"));
        assertThat(outcome.providerExchanges()).hasSize(3);
    }

    @Test void hallucinatedShellIsBlockedBeforeAnyObserverCall() {
        var reads = new AtomicInteger();
        var outcome = agent(new ScriptedProvider(OpsDecision.Disposition.ESCALATE, false, true),
            OpsDecisionAgent.Limits.defaults()).decide(app(), (a,p) -> { reads.incrementAndGet(); return observer(Status.STOPPED).observe(a,p); });
        assertThat(reads).hasValue(0);
        assertThat(outcome.evidence()).isEmpty();
        assertThat(outcome.origin()).isEqualTo(OpsDecisionAgent.Origin.MODEL);
        assertThat(outcome.providerExchanges().get(1).request().messages())
            .anyMatch(m -> m.role() == Role.TOOL && m.content().contains("not allowed in REMOTE_READ_ONLY scope"));
    }

    @Test void exhaustedProviderBudgetIsSystemHandoffNotModelSuccess() {
        var provider = new ScriptedProvider(OpsDecision.Disposition.PROPOSE_ACTION, false, false);
        var outcome = agent(provider, new OpsDecisionAgent.Limits(Duration.ofSeconds(30), 30_000, 1, 8))
            .decide(app(), observer(Status.STOPPED));
        assertThat(provider.calls).hasValue(1);
        assertThat(outcome.origin()).isEqualTo(OpsDecisionAgent.Origin.SYSTEM);
        assertThat(outcome.decision().disposition()).isEqualTo(OpsDecision.Disposition.ESCALATE);
        assertThat(outcome.failureType()).isNotNull();
    }
    @Test void controllerBaselineIsAvailableAndDuplicateReadFactsDoNotChangeTheSymptomFingerprint() throws Exception {
        var ledger=new DecisionEvidenceLedger(app(),CLOCK);
        collectFour(ledger,observer(Status.STOPPED));
        var outcome=agent(new ScriptedProvider(OpsDecision.Disposition.PROPOSE_ACTION,false,false),OpsDecisionAgent.Limits.defaults())
            .decide(app(),observer(Status.STOPPED),com.clawkit.tools.control.ExecutionControl.none(),
                new OpsDecisionAgent.DecisionContext("inc-context",1,null,ledger.snapshot()));
        assertThat(outcome.origin()).isEqualTo(OpsDecisionAgent.Origin.MODEL);
        assertThat(outcome.evidence()).hasSize(8);
        assertThat(ManagedContracts.snapshot(outcome.evidence())).isEqualTo(ManagedContracts.snapshot(ledger.snapshot()));
        assertThat(outcome.providerExchanges().getFirst().request().messages())
            .anyMatch(m -> m.role()==Role.USER && m.content().contains("inc-context") && m.content().contains(ledger.snapshot().getFirst().id()));
    }
    @Test void cancelledControllerCannotStartAProviderCall() {
        var cancellation=com.clawkit.reliability.CancellationTree.unbounded(); cancellation.cancel();
        var provider=new ScriptedProvider(OpsDecision.Disposition.PROPOSE_ACTION,false,false);
        var outcome=agent(provider,OpsDecisionAgent.Limits.defaults()).decide(app(),observer(Status.STOPPED),cancellation);
        assertThat(provider.calls).hasValue(0);
        assertThat(outcome.origin()).isEqualTo(OpsDecisionAgent.Origin.SYSTEM);
        assertThat(outcome.providerExchanges()).isEmpty();
        assertThat(outcome.evidence()).isEmpty();
    }

    @Test void unknownPlaybookExtraFieldsAndCoercedTypesAreRejected() {
        for (String mutation : List.of("playbook", "permission", "reason")) {
            var provider = new ScriptedProvider(OpsDecision.Disposition.PROPOSE_ACTION, false, false) {
                @Override ObjectNode submission(List<String> refs) {
                    ObjectNode node = super.submission(refs);
                    if (mutation.equals("permission")) node.put("permission", "AUTO");
                    else if (mutation.equals("reason")) node.put("reason", 1);
                    else node.put("playbook", "DELETE_DATABASE");
                    return node;
                }
            };
            var outcome = agent(provider, new OpsDecisionAgent.Limits(Duration.ofSeconds(30), 30_000, 3, 8))
                .decide(app(), observer(Status.STOPPED));
            assertThat(outcome.origin()).isEqualTo(OpsDecisionAgent.Origin.SYSTEM);
            assertThat(outcome.rejectedSubmissions()).isNotEmpty();
        }
    }

    @Test void stoppedIntentMaintenanceAndStatefulApplicationsCannotProposeRepairs() throws Exception {
        ManagedApplication base = app();
        for (ManagedApplication denied : List.of(
            copy(base, false, base.desiredState(), null),
            copy(base, true, ManagedApplication.DesiredState.STOPPED, null),
            copy(base, true, base.desiredState(), NOW.plusSeconds(60)))) {
            var ledger = new DecisionEvidenceLedger(denied, CLOCK);
            List<String> refs = collectFour(ledger, observer(Status.STOPPED));
            assertThatThrownBy(() -> ledger.submit(proposal(refs)))
                .hasMessageContaining("forbids repair");
        }
    }

    @Test void proposalRequiresFreshEvidenceAndCannotHideCounterevidence() throws Exception {
        var ledger = new DecisionEvidenceLedger(app(), CLOCK);
        List<String> refs = collectFour(ledger, observer(Status.STOPPED));
        ledger.collect((a,p) -> new Observation(a.targetId(),a.composeProject(),a.service(),p,NOW,
            Status.UNHEALTHY,"dependency failed"), Probe.DEPENDENCIES);
        assertThatThrownBy(() -> ledger.submit(proposal(refs))).hasMessageContaining("conflicting/unknown DEPENDENCIES");
        var stale = new DecisionEvidenceLedger(app(), CLOCK);
        List<String> staleRefs = collectFour(stale, (a,p) -> {
            Observation o = observer(Status.STOPPED).observe(a,p);
            return new Observation(o.targetId(),o.composeProject(),o.service(),p,NOW.minusSeconds(100),o.status(),o.detail());
        });
        assertThatThrownBy(() -> stale.submit(proposal(staleRefs))).hasMessageContaining("missing fresh cited");
    }

    @Test void serviceRestartingAndMissingDependenciesPreventProposal() throws Exception {
        var ledger = new DecisionEvidenceLedger(app(), CLOCK);
        List<String> refs = collectFour(ledger, observer(Status.RESTARTING));
        assertThatThrownBy(() -> ledger.submit(proposal(refs))).hasMessageContaining("conflicting/unknown SERVICE");
        var missing = new DecisionEvidenceLedger(app(), CLOCK);
        List<String> partial = new ArrayList<>();
        for (Probe p : List.of(Probe.SERVICE, Probe.HEALTH, Probe.BUSINESS)) partial.add(missing.collect(observer(Status.STOPPED),p).id());
        assertThatThrownBy(() -> missing.submit(proposal(partial))).hasMessageContaining("DEPENDENCIES");
    }

    @Test void reviewedRestartRequiresRunningUnhealthyBusinessAndHealthyDependencies() throws Exception {
        var ledger = new DecisionEvidenceLedger(app(),CLOCK);
        List<String> refs = collectFour(ledger,observer(Status.RUNNING));
        ledger.submit(new OpsDecision(OpsDecision.Disposition.PROPOSE_ACTION,"business failed while running",refs,
            OpsDecision.Playbook.RESTART_UNHEALTHY_V1,List.of(),null));
        assertThat(ledger.submitted().playbook()).isEqualTo(OpsDecision.Playbook.RESTART_UNHEALTHY_V1);
        var healthy = new DecisionEvidenceLedger(app(),CLOCK);
        List<String> healthyRefs = collectFour(healthy,(a,p) -> {
            Observation o = observer(Status.RUNNING).observe(a,p);
            return p == Probe.BUSINESS ? new Observation(o.targetId(),o.composeProject(),o.service(),p,NOW,Status.HEALTHY,"healthy business") : o;
        });
        assertThatThrownBy(() -> healthy.submit(new OpsDecision(OpsDecision.Disposition.PROPOSE_ACTION,"restart",healthyRefs,
            OpsDecision.Playbook.RESTART_UNHEALTHY_V1,List.of(),null))).hasMessageContaining("BUSINESS");
    }

    @Test void wrongTargetFutureObservationAndSecondSubmissionAreRejected() throws Exception {
        var ledger = new DecisionEvidenceLedger(app(), CLOCK);
        assertThatThrownBy(() -> ledger.collect((a,p) -> new Observation("other",a.composeProject(),a.service(),p,NOW,Status.STOPPED,""), Probe.SERVICE))
            .hasMessageContaining("different target");
        assertThatThrownBy(() -> ledger.collect((a,p) -> new Observation(a.targetId(),a.composeProject(),a.service(),p,NOW.plusSeconds(1),Status.STOPPED,""), Probe.SERVICE))
            .hasMessageContaining("future");
        ledger.submit(OpsDecision.systemEscalation("handoff"));
        assertThatThrownBy(() -> ledger.submit(OpsDecision.systemEscalation("again"))).hasMessageContaining("already submitted");
        assertThatThrownBy(() -> ledger.collect(observer(Status.STOPPED), Probe.SERVICE)).hasMessageContaining("already submitted");
    }

    @Test void shadowExpiredRevokedAndVersionDriftCannotGrantAutoPermission() {
        ManagedApplication app = app();
        var playbooks = Set.of(OpsDecision.Playbook.START_STOPPED_V1);
        ActionPolicy policy = new ActionPolicy(app.id(),1,1,ActionPolicy.Mode.LIMITED_AUTO,
            ActionPolicy.Qualification.QUALIFIED,playbooks,NOW.plusSeconds(60),1);
        assertThat(policy.permitsAuto(app,OpsDecision.Playbook.START_STOPPED_V1,NOW)).isTrue();
        assertThat(policy.permitsAuto(app,OpsDecision.Playbook.RESTART_UNHEALTHY_V1,NOW)).isFalse();
        assertThat(policy.permitsAuto(app,OpsDecision.Playbook.START_STOPPED_V1,NOW.plusSeconds(60))).isFalse();
        for (ActionPolicy.Qualification q : List.of(ActionPolicy.Qualification.SHADOW,ActionPolicy.Qualification.REVOKED,ActionPolicy.Qualification.DRAFT))
            assertThat(new ActionPolicy(app.id(),1,1,ActionPolicy.Mode.LIMITED_AUTO,q,playbooks,NOW.plusSeconds(60),1)
                .permitsAuto(app,OpsDecision.Playbook.START_STOPPED_V1,NOW)).isFalse();
        assertThat(new ActionPolicy(app.id(),2,1,policy.mode(),policy.qualification(),playbooks,policy.expiresAt(),1)
            .permitsAuto(app,OpsDecision.Playbook.START_STOPPED_V1,NOW)).isFalse();
        assertThat(ActionPolicy.ask(app,NOW.plusSeconds(60)).permitsAuto(app,OpsDecision.Playbook.START_STOPPED_V1,NOW)).isFalse();
        assertThat(policy.policyHash()).hasSize(64).isNotEqualTo(ActionPolicy.ask(app,NOW.plusSeconds(60)).policyHash());
    }

    @Test void applicationRegistrationRejectsUnboundedOrRemoteInputs() {
        var app = app();
        assertThatThrownBy(() -> new ManagedApplication(app.id(),app.targetId(),app.composeProject(),"demo; rm",1,true,
            app.desiredState(),null,app.healthUri(),app.businessUri(),app.businessMarker(),app.checkInterval(),app.evidenceTtl()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ManagedApplication(app.id(),app.targetId(),app.composeProject(),app.service(),1,true,
            app.desiredState(),null,URI.create("http://example.com/"),app.businessUri(),app.businessMarker(),app.checkInterval(),app.evidenceTtl()))
            .hasMessageContaining("loopback");
    }

    private OpsDecisionAgent agent(LLMProvider provider, OpsDecisionAgent.Limits limits) {
        return new OpsDecisionAgent(provider, workspace, new CompositeRunRecorder(), CLOCK, limits);
    }

    @Test void truncatedButValidToolPayloadCannotExecuteAndKeepsOriginalUsage() {
        var provider = new ScriptedProvider(OpsDecision.Disposition.ESCALATE,false,false) {
            @Override public ModelResponse generate(ModelRequest request) {
                assertThat(request.parameters().maxTokens()).isEqualTo(4096);
                var response=super.generate(request);
                return new ModelResponse(response.content(),response.toolCalls(),FinishReason.LENGTH,response.usage(),response.metadata());
            }
        };
        var reads=new AtomicInteger();
        var outcome=agent(provider,OpsDecisionAgent.Limits.defaults()).decide(app(),(a,p) -> {
            reads.incrementAndGet(); return observer(Status.STOPPED).observe(a,p);
        });
        assertThat(reads).hasValue(0);
        assertThat(outcome.origin()).isEqualTo(OpsDecisionAgent.Origin.SYSTEM);
        assertThat(outcome.failureType()).isEqualTo("MODEL_PROTOCOL_OUTPUT_TRUNCATED");
        assertThat(outcome.providerExchanges()).singleElement().satisfies(e -> {
            assertThat(e.usage().totalTokens()).isEqualTo(150);
            assertThat(e.response().finishReason()).isEqualTo(FinishReason.LENGTH);
        });
        assertThatThrownBy(() -> new OpsDecisionAgent.ModelSettings(16_385,ProviderReasoningMode.PROVIDER_DEFAULT))
            .isInstanceOf(IllegalArgumentException.class);
    }
    private static ManagedApplication copy(ManagedApplication a, boolean stateless, ManagedApplication.DesiredState desired, Instant maintenance) {
        return new ManagedApplication(a.id(),a.targetId(),a.composeProject(),a.service(),a.version(),stateless,desired,
            maintenance,a.healthUri(),a.businessUri(),a.businessMarker(),a.checkInterval(),a.evidenceTtl());
    }
    private static List<String> collectFour(DecisionEvidenceLedger ledger, ManagedObserver observer) throws Exception {
        List<String> refs = new ArrayList<>();
        for (Probe p : List.of(Probe.SERVICE,Probe.HEALTH,Probe.BUSINESS,Probe.DEPENDENCIES)) refs.add(ledger.collect(observer,p).id());
        return refs;
    }
    private static OpsDecision proposal(List<String> refs) {
        return new OpsDecision(OpsDecision.Disposition.PROPOSE_ACTION,"registered service exited",refs,
            OpsDecision.Playbook.START_STOPPED_V1,List.of(),null);
    }

    static class ScriptedProvider implements LLMProvider {
        final AtomicInteger calls = new AtomicInteger();
        private final OpsDecision.Disposition disposition;
        private final boolean fakeRef, shell;
        ScriptedProvider(OpsDecision.Disposition disposition, boolean fakeRef, boolean shell) {
            this.disposition = disposition; this.fakeRef = fakeRef; this.shell = shell;
        }
        @Override public ModelResponse generate(ModelRequest request) {
            int call = calls.incrementAndGet();
            List<ToolCall> tools;
            if (call == 1) {
                List<String> names = shell ? List.of("bash") : List.of("read_service","read_health","read_business","read_dependencies");
                tools = names.stream().map(n -> new ToolCall("c-"+n, shell ? n : "mcp__remote_managed_ops__"+n, JSON.createObjectNode())).toList();
            } else {
                List<String> refs = new ArrayList<>();
                request.messages().stream().filter(m -> m.role() == Role.TOOL).forEach(m -> {
                    try {
                        var n = JSON.readTree(m.content());
                        if (n.has("id")) refs.add(n.path("id").asText());
                    } catch (Exception ignored) {}
                });
                ObjectNode submit = submission(refs);
                if (fakeRef && call == 2) submit.withArray("evidenceRefs").add("ev-fabricated");
                tools = List.of(new ToolCall("c-submit-"+call,"mcp__remote_managed_ops__submit_decision",submit));
            }
            return new ModelResponse(null,tools,FinishReason.TOOL_CALLS,new TokenUsage(100,50,150),ProviderResponseMetadata.EMPTY);
        }
        ObjectNode submission(List<String> refs) {
            var node = JSON.createObjectNode();
            node.put("disposition",disposition.name()).put("reason","scripted branch for control-flow verification");
            refs.forEach(node.putArray("evidenceRefs")::add);
            if (refs.isEmpty()) node.putArray("evidenceRefs");
            if (disposition == OpsDecision.Disposition.PROPOSE_ACTION) node.put("playbook","START_STOPPED_V1");
            else node.putNull("playbook");
            var next = node.putArray("nextProbes");
            if (disposition == OpsDecision.Disposition.INVESTIGATE) next.add("LOGS");
            if (disposition == OpsDecision.Disposition.WAIT || disposition == OpsDecision.Disposition.INVESTIGATE) node.put("recheckAfterSeconds",5);
            else node.putNull("recheckAfterSeconds");
            return node;
        }
        @Override public Message generate(List<Message> messages,List<ToolDefinition> tools) { throw new AssertionError("typed provider required"); }
    }
}
