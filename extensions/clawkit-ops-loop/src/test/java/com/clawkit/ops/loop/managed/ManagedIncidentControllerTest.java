package com.clawkit.ops.loop.managed;

import com.clawkit.observability.CompositeRunRecorder;
import com.clawkit.ops.loop.automation.*;
import com.clawkit.tools.action.EffectCertainty;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;

/** Controller contracts use test doubles; no actual model/container success rate is derived from these tests. */
class ManagedIncidentControllerTest {
    @TempDir Path root;
    final MutableClock clock=new MutableClock();

    @Test void repeatedAbnormalObservationsMergeOneIncidentWithoutRepeatedDecisions() throws Exception {
        try (var fixture=new Fixture(ActionPolicy.Mode.ASK)) {
            fixture.start(); fixture.controller.tick();
            String id=fixture.controller.status().current().id();
            for (int i=0;i<5;i++) { clock.advance(Duration.ofSeconds(1)); fixture.controller.tick(); }
            assertThat(fixture.decisions).hasValue(1); assertThat(fixture.writes).hasValue(0);
            assertThat(fixture.controller.status().current().id()).isEqualTo(id);
            assertThat(fixture.controller.status().current().observations()).isEqualTo(6);
            assertThat(fixture.controller.status().current().state()).isEqualTo(ManagedIncident.State.AWAITING_APPROVAL);
            assertThat(fixture.events.stream().filter(e -> e.kind().equals("INCIDENT_CREATED"))).hasSize(1);
            String artifact=Files.readString(fixture.store.artifactPath(fixture.controller.status().current().decisionArtifact()));
            assertThat(artifact).contains("providerUsage").doesNotContain("providerExchanges","runtimeResponse","test double");
        }
    }

    @Test void qualifiedAutomaticDecisionIsConsumedAndOnlyOneRepairIsDispatched() throws Exception {
        try (var fixture=new Fixture(ActionPolicy.Mode.LIMITED_AUTO)) {
            fixture.start(); fixture.controller.tick();
            assertThat(fixture.controller.status().current().state()).isEqualTo(ManagedIncident.State.RECOVERED);
            assertThat(fixture.writes).hasValue(1); assertThat(fixture.decisions).hasValue(1);
            assertThat(fixture.store.observations().source()).isEqualTo("INDEPENDENT_VERIFICATION");
            assertThat(fixture.store.observations().observations()).allMatch(o -> o.status()==Status.RUNNING || o.status()==Status.HEALTHY);
            assertThat(fixture.store.observations().observations()).extracting(Observation::probe).containsExactly(Probe.SERVICE,Probe.HEALTH,Probe.BUSINESS);
            fixture.controller.tick(); fixture.controller.tick();
            assertThat(fixture.writes).hasValue(1); assertThat(fixture.decisions).hasValue(1);
            assertThat(fixture.controller.status().lastTick()).isEqualTo(clock.instant());
        }
    }

    @Test void explicitHumanApprovalUsesFreshEvidenceAndSharedRepairPath() throws Exception {
        try (var fixture=new Fixture(ActionPolicy.Mode.ASK)) {
            fixture.start(); fixture.controller.tick();
            String id=fixture.controller.status().current().id(); fixture.controller.approve(id,"test-user");
            assertThat(fixture.writes).hasValue(1);
            assertThat(fixture.controller.status().current().state()).isEqualTo(ManagedIncident.State.RECOVERED);
            String report=Files.readString(fixture.store.artifactPath(fixture.controller.status().current().repair().artifact()));
            assertThat(report).contains("test-user").doesNotContain("policyHash");
        }
    }
    @Test void revokedKnowledgeRejectsQueuedApprovalBeforeRepairAndPreservesHandoff() throws Exception {
        try(var fixture=new Fixture(ActionPolicy.Mode.ASK)) {
            var knowledge=ManagedKnowledgeStoreTest.ready(fixture.app,root.resolve("knowledge"));
            var ref=knowledge.reference("start-guidance",1); fixture.knowledgeReferences=List.of(ref);
            fixture.controller.configureKnowledge(knowledge); fixture.start(); fixture.controller.tick();
            String incident=fixture.controller.status().current().id();
            knowledge.revoke(ref,"human","replay no longer trusted");
            assertThatThrownBy(() -> fixture.controller.approve(incident,"human")).hasMessageContaining("not currently reviewed");
            assertThat(fixture.writes).hasValue(0);
            assertThat(fixture.controller.status().current().state()).isEqualTo(ManagedIncident.State.HANDOFF);
            assertThat(fixture.events).anyMatch(e -> e.kind().equals("KNOWLEDGE_HANDOFF"));
        }
    }

    @Test void rejectionSuppressesRepeatFaultUntilIndependentRecovery() throws Exception {
        try (var fixture=new Fixture(ActionPolicy.Mode.ASK)) {
            fixture.start(); fixture.controller.tick();
            fixture.controller.reject(fixture.controller.status().current().id());
            fixture.controller.tick(); fixture.controller.tick();
            assertThat(fixture.writes).hasValue(0); assertThat(fixture.decisions).hasValue(1);
            assertThat(fixture.controller.status().current().state()).isEqualTo(ManagedIncident.State.CANCELLED);
            fixture.running=true; fixture.healthy=true; fixture.controller.tick();
            assertThat(fixture.controller.status().current().state()).isEqualTo(ManagedIncident.State.RECOVERED);
        }
    }

    @Test void pauseResumeAndStopControlObservationAndDecisionStarts() throws Exception {
        try (var fixture=new Fixture(ActionPolicy.Mode.ASK)) {
            fixture.start(); fixture.controller.pause(); fixture.controller.tick();
            assertThat(fixture.controller.status().current()).isNull(); assertThat(fixture.reads).hasValue(0);
            fixture.controller.resume(); fixture.controller.tick();
            long observations=fixture.controller.status().current().observations();
            fixture.controller.stop(); fixture.controller.tick();
            assertThat(fixture.controller.status().current().observations()).isEqualTo(observations);
            assertThat(fixture.scheduler.schedules).hasValue(1);
        }
    }

    @Test void pauseBetweenDecisionAndDispatchPreventsRepair() throws Exception {
        try (var fixture=new Fixture(ActionPolicy.Mode.LIMITED_AUTO)) {
            fixture.extraListener=event -> {
                if (event.kind().equals("DECISION_SUBMITTED")) {
                    try { fixture.store.requestMode(ManagedIncidentStore.Mode.PAUSED,clock.instant()); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                }
            };
            fixture.start(); fixture.controller.tick();
            assertThat(fixture.writes).hasValue(0);
            assertThat(fixture.controller.status().current().state()).isEqualTo(ManagedIncident.State.AWAITING_APPROVAL);
        }
    }

    @Test void waitingRechecksAreBoundedAndSelfRecoveryDoesNotRepair() throws Exception {
        try (var fixture=new Fixture(ActionPolicy.Mode.LIMITED_AUTO)) {
            fixture.disposition=OpsDecision.Disposition.WAIT;
            fixture.start(); fixture.controller.tick(); fixture.controller.tick();
            assertThat(fixture.decisions).hasValue(1);
            clock.advance(Duration.ofSeconds(5)); fixture.controller.tick(); assertThat(fixture.decisions).hasValue(2);
            fixture.running=true; fixture.healthy=true; fixture.controller.tick();
            assertThat(fixture.controller.status().current().state()).isEqualTo(ManagedIncident.State.RECOVERED);
            assertThat(fixture.writes).hasValue(0);
        }
    }

    @Test void investigateCollectsRequestedProbeAndDecisionCapEscalates() throws Exception {
        try (var fixture=new Fixture(ActionPolicy.Mode.LIMITED_AUTO)) {
            fixture.disposition=OpsDecision.Disposition.INVESTIGATE;
            fixture.start(); fixture.controller.tick();
            clock.advance(Duration.ofSeconds(5)); fixture.controller.tick();
            assertThat(fixture.contexts.get(1).baseline()).anyMatch(e -> e.observation().probe()==Probe.LOGS);
            clock.advance(Duration.ofSeconds(5)); fixture.controller.tick();
            assertThat(fixture.decisions).hasValue(2);
            assertThat(fixture.controller.status().current().state()).isEqualTo(ManagedIncident.State.HANDOFF);
            assertThat(fixture.writes).hasValue(0);
        }
    }

    @Test void missingOptionalSourcesDoNotPreventIndependentSelfRecovery() throws Exception {
        try (var fixture=new Fixture(ActionPolicy.Mode.LIMITED_AUTO)) {
            fixture.disposition=OpsDecision.Disposition.INVESTIGATE;
            fixture.investigationProbes=List.of(Probe.RESOURCES,Probe.CHANGES,Probe.METRICS);
            fixture.start(); fixture.controller.tick();
            fixture.running=true; fixture.healthy=true;
            clock.advance(Duration.ofSeconds(5)); fixture.controller.tick();
            assertThat(fixture.controller.status().current().state()).isEqualTo(ManagedIncident.State.RECOVERED);
            assertThat(fixture.decisions).hasValue(1); assertThat(fixture.writes).hasValue(0);
            assertThat(fixture.store.observations().source()).isEqualTo("INDEPENDENT_VERIFICATION");
        }
    }

    @Test void importantChangedEvidenceCanReevaluateBeforeLongWaitExpires() throws Exception {
        try (var fixture=new Fixture(ActionPolicy.Mode.LIMITED_AUTO)) {
            fixture.disposition=OpsDecision.Disposition.WAIT; fixture.recheckSeconds=60;
            fixture.start(); fixture.controller.tick();
            fixture.running=true; // still failing business, important service-state change
            clock.advance(Duration.ofSeconds(1)); fixture.controller.tick(); assertThat(fixture.decisions).hasValue(1);
            clock.advance(Duration.ofSeconds(1)); fixture.controller.tick(); assertThat(fixture.decisions).hasValue(2);
        }
    }

    @Test void intentStoppedOrMaintenancePreventsDiscoveryAndModelCalls() throws Exception {
        for (boolean maintenance:List.of(false,true)) {
            try (var fixture=new Fixture(ActionPolicy.Mode.LIMITED_AUTO,root.resolve(maintenance ? "maintenance" : "stopped"))) {
                var a=fixture.app;
                fixture.app=new ManagedApplication(a.id(),a.targetId(),a.composeProject(),a.service(),a.version(),true,
                    maintenance ? ManagedApplication.DesiredState.RUNNING : ManagedApplication.DesiredState.STOPPED,
                    maintenance ? clock.instant().plusSeconds(60) : null,a.healthUri(),a.businessUri(),a.businessMarker(),a.checkInterval(),a.evidenceTtl());
                fixture.start(); fixture.controller.tick();
                assertThat(fixture.reads).hasValue(0); assertThat(fixture.decisions).hasValue(0); assertThat(fixture.writes).hasValue(0);
            }
        }
    }

    @Test void unknownExecutionHandoffIsStickyEvenAfterLaterHealthyObservation() throws Exception {
        try (var fixture=new Fixture(ActionPolicy.Mode.LIMITED_AUTO)) {
            fixture.unknown=true; fixture.start(); fixture.controller.tick();
            assertThat(fixture.controller.status().current().state()).isEqualTo(ManagedIncident.State.HANDOFF);
            fixture.running=true; fixture.healthy=true; fixture.controller.tick();
            assertThat(fixture.controller.status().current().state()).isEqualTo(ManagedIncident.State.HANDOFF);
            assertThat(fixture.writes).hasValue(1); assertThat(fixture.decisions).hasValue(1);
        }
    }

    @Test void processLeasePreventsAnotherControllerAndStateSurvivesReopen() throws Exception {
        String id;
        try (var fixture=new Fixture(ActionPolicy.Mode.ASK)) {
            fixture.start(); fixture.controller.tick(); id=fixture.controller.status().current().id();
            assertThatThrownBy(() -> fixture.store.claim()).isInstanceOf(java.io.IOException.class);
        }
        try (var fixture=new Fixture(ActionPolicy.Mode.ASK)) {
            assertThat(fixture.controller.status().current().id()).isEqualTo(id);
            fixture.start(); fixture.controller.tick();
            assertThat(fixture.decisions).hasValue(0); assertThat(fixture.writes).hasValue(0);
        }
    }

    @Test void pendingEventReplayKeepsSameIdentityForOutboxDeduplication() throws Exception {
        try (var fixture=new Fixture(ActionPolicy.Mode.ASK)) {
            fixture.extraListener=event -> { throw new IllegalStateException("local outbox unavailable"); };
            fixture.start(); assertThatThrownBy(fixture.controller::tick).isInstanceOf(IllegalStateException.class);
            var pending=fixture.store.read().pendingEvent(); assertThat(pending).isNotNull();
            fixture.extraListener=event -> {};
            fixture.controller.tick();
            assertThat(fixture.events.stream().filter(e -> e.id().equals(pending.id()))).hasSize(2);
            assertThat(fixture.store.read().pendingEvent()).isNull();
            assertThat(fixture.writes).hasValue(0);
        }
    }
    @Test void externalPauseDuringSlowFreshPrecheckPreventsDispatch() throws Exception {
        try (var fixture=new Fixture(ActionPolicy.Mode.LIMITED_AUTO)) {
            fixture.beforeRead=() -> {
                if (fixture.reads.get()==5) {
                    try { fixture.store.requestMode(ManagedIncidentStore.Mode.PAUSED,clock.instant()); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                }
            };
            fixture.start(); fixture.controller.tick();
            assertThat(fixture.writes).hasValue(0);
            assertThat(fixture.store.requestedMode()).isEqualTo(ManagedIncidentStore.Mode.PAUSED);
            assertThat(fixture.controller.status().current().state()).isEqualTo(ManagedIncident.State.HANDOFF);
        }
    }
    @Test void selfRecoveryBetweenDecisionAndPrecheckIsIndependentlyVerifiedWithoutDispatch() throws Exception {
        try (var fixture=new Fixture(ActionPolicy.Mode.LIMITED_AUTO)) {
            fixture.beforeRead=() -> { if (fixture.reads.get()==5) { fixture.running=true; fixture.healthy=true; } };
            fixture.start(); fixture.controller.tick();
            assertThat(fixture.writes).hasValue(0);
            assertThat(fixture.controller.status().current().state()).isEqualTo(ManagedIncident.State.RECOVERED);
            assertThat(fixture.controller.status().current().repair().status()).isEqualTo(ManagedRepairExecutor.Status.NO_EFFECT);
            assertThat(fixture.controller.status().current().detail()).contains("self-recovery");
        }
    }
    @Test void queuedConsentCannotChangeTargetActionOrOutliveItsExpiry() throws Exception {
        try (var fixture=new Fixture(ActionPolicy.Mode.ASK)) {
            fixture.start(); fixture.controller.tick();
            var queue=new ManagedCommandInbox(root.resolve("inbox"),clock);
            var command=queue.enqueue(ManagedCommandInbox.Type.APPROVE,fixture.controller.status().current(),"human");
            var changed=new ManagedCommandInbox.Command(command.id(),command.type(),command.incidentId(),"wrong-application-hash",command.playbook(),
                command.operator(),command.issuedAt(),command.expiresAt());
            assertThatThrownBy(() -> fixture.controller.apply(changed)).hasMessageContaining("differs"); assertThat(fixture.writes).hasValue(0);
            clock.advance(Duration.ofSeconds(301));
            assertThatThrownBy(() -> fixture.controller.apply(command)).hasMessageContaining("expired"); assertThat(fixture.writes).hasValue(0);
        }
    }
    @Test void queuedConsentIsConsumedByTheSameReliableExecutionPathWithoutReplay() throws Exception {
        try (var fixture=new Fixture(ActionPolicy.Mode.ASK)) {
            fixture.start(); fixture.controller.tick();
            var queue=new ManagedCommandInbox(root.resolve("inbox"),clock);
            var command=queue.enqueue(ManagedCommandInbox.Type.APPROVE,fixture.controller.status().current(),"human");
            fixture.controller.apply(command);
            assertThat(fixture.writes).hasValue(1); assertThat(fixture.controller.status().current().state()).isEqualTo(ManagedIncident.State.RECOVERED);
            assertThatThrownBy(() -> fixture.controller.apply(command)).hasMessageContaining("not awaiting"); assertThat(fixture.writes).hasValue(1);
        }
    }

    private final class Fixture implements AutoCloseable {
        ManagedApplication app=ManagedDecisionTest.app();
        final ActionPolicy.Mode mode;
        final AtomicInteger decisions=new AtomicInteger(),writes=new AtomicInteger(),reads=new AtomicInteger();
        final List<ManagedIncidentStore.Event> events=new ArrayList<>();
        final List<OpsDecisionAgent.DecisionContext> contexts=new ArrayList<>();
        final ManagedIncidentStore store;
        final ManagedRepairExecutor executor;
        final ManualScheduler scheduler=new ManualScheduler();
        final ManagedIncidentController controller;
        boolean running,healthy,unknown;
        OpsDecision.Disposition disposition=OpsDecision.Disposition.PROPOSE_ACTION;
        List<Probe> investigationProbes=List.of(Probe.LOGS);
        List<OpsKnowledge.Reference> knowledgeReferences=List.of();
        int recheckSeconds=5;
        java.util.function.Consumer<ManagedIncidentStore.Event> extraListener=event -> {};
        Runnable beforeRead=() -> {};
        Fixture(ActionPolicy.Mode mode) throws Exception { this(mode,root); }
        Fixture(ActionPolicy.Mode mode,Path state) throws Exception {
            this.mode=mode;
            store=new ManagedIncidentStore(state.resolve("incidents"),app.id());
            var verifier=new IndependentManagedVerifier(clock,new IndependentManagedVerifier.Settings(3,3,Duration.ofMillis(100)),clock::advance);
            executor=new ManagedRepairExecutor(state.resolve("repair"),clock,new CompositeRunRecorder(),verifier);
            controller=new ManagedIncidentController(() -> app,this::policy,() -> this::observe,(a,observer,control,context) -> {
                decisions.incrementAndGet(); contexts.add(context);
                var refs=context.baseline().stream().map(DecisionEvidence::id).toList();
                var d=new OpsDecision(disposition,"scripted controller contract",refs,
                    disposition==OpsDecision.Disposition.PROPOSE_ACTION ? OpsDecision.Playbook.START_STOPPED_V1 : null,
                    disposition==OpsDecision.Disposition.INVESTIGATE ? investigationProbes : List.of(),
                    disposition==OpsDecision.Disposition.INVESTIGATE || disposition==OpsDecision.Disposition.WAIT ? recheckSeconds : null);
                return new OpsDecisionAgent.Outcome(OpsDecisionAgent.Origin.MODEL,d,context.baseline(),List.of(),List.of(),"test double",null,null,knowledgeReferences);
            },(a,p) -> {
                writes.incrementAndGet();
                if (unknown) return new ManagedFixAdapter.ExecutionReport(EffectCertainty.EFFECT_UNKNOWN,"response lost");
                running=true; healthy=true; return new ManagedFixAdapter.ExecutionReport(EffectCertainty.EFFECT_CONFIRMED,"exit zero");
            },executor,verifier,store,scheduler,new ManagedIncidentController.Settings(2,Duration.ofSeconds(60),Duration.ofSeconds(2)),clock,event -> {
                events.add(event); extraListener.accept(event);
            });
        }
        ActionPolicy policy() {
            return new ActionPolicy(app.id(),app.version(),1,mode,ActionPolicy.Qualification.QUALIFIED,
                Set.of(OpsDecision.Playbook.values()),ManagedDecisionTest.NOW.plusSeconds(600),1);
        }
        Observation observe(ManagedApplication a,Probe p) {
            reads.incrementAndGet();
            beforeRead.run();
            return new Observation(a.targetId(),a.composeProject(),a.service(),p,clock.instant(),switch(p) {
                case SERVICE -> running ? Status.RUNNING : Status.STOPPED;
                case DEPENDENCIES -> Status.HEALTHY;
                case LOGS,RESOURCES,CHANGES,METRICS -> Status.UNKNOWN;
                default -> healthy ? Status.HEALTHY : Status.UNHEALTHY;
            },"normalized controller fixture");
        }
        void start() throws Exception { controller.start(); }
        @Override public void close() throws Exception { try { controller.close(); } finally { executor.close(); } }
    }
    private static final class MutableClock extends Clock {
        Instant at=ManagedDecisionTest.NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return at; }
        void advance(Duration duration) { at=at.plus(duration); }
    }
    private static final class ManualScheduler implements AutomationTaskScheduler {
        final AtomicInteger schedules=new AtomicInteger();
        @Override public ScheduledHandle schedule(Runnable task,long initial,long delay,TimeUnit unit) {
            schedules.incrementAndGet(); return new ScheduledHandle() {
                boolean cancelled;
                @Override public boolean cancel(boolean interrupt) { cancelled=true; return true; }
                @Override public boolean isCancelled() { return cancelled; }
            };
        }
        @Override public void close() {}
    }
}
