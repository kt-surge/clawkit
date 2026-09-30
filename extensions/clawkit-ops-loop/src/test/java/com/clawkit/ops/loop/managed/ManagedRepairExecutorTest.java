package com.clawkit.ops.loop.managed;

import com.clawkit.observability.CompositeRunRecorder;
import com.clawkit.reliability.attempt.*;
import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.control.ExecutionControl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;
import static org.assertj.core.api.Assertions.*;

class ManagedRepairExecutorTest {
    @TempDir Path root;
    final MutableClock clock=new MutableClock();
    final ManagedApplication app=ManagedDecisionTest.app();

    @Test void humanAndPolicyShareExecutorJournalAndIndependentVerification() throws Exception {
        for (boolean human:List.of(false,true)) {
            Path state=root.resolve(human ? "human" : "auto");
            Fixture fixture=new Fixture();
            Proposal proposal=proposal(fixture);
            var request=human ? RepairAuthorization.approved("inc-1",app,proposal.decision(),proposal.evidence(),"test-user",clock)
                : RepairAuthorization.Automatic.INSTANCE;
            try (var executor=executor(state)) {
                var result=execute(executor,"inc-1",proposal,request,fixture,() -> policy(),fixture::repair);
                assertThat(result.status()).isEqualTo(ManagedRepairExecutor.Status.RECOVERED);
                assertThat(result.attemptState()).isEqualTo(AttemptState.VERIFIED_SUCCESS);
                assertThat(fixture.writes).hasValue(1);
                assertThat(result.verification().samples()).hasSize(3).allMatch(IndependentManagedVerifier.Sample::healthy);
                assertThat(fixture.opens).hasValue(4); // one precheck + three independent sessions
                String authorization=Files.readString(state.resolve("control/authorization-"+result.attemptId()+".json"));
                assertThat(authorization).contains(human ? "HUMAN" : "POLICY");
                if (human) assertThat(result.authorization()).isInstanceOf(RepairAuthorization.Human.class);
                else assertThat(result.authorization()).isInstanceOf(RepairAuthorization.Policy.class);
                var repeat=execute(executor,"inc-1",proposal,request,fixture,() -> policy(),fixture::repair);
                assertThat(repeat.status()).isEqualTo(ManagedRepairExecutor.Status.BLOCKED);
                assertThat(fixture.writes).hasValue(1);
            }
        }
    }

    @Test void unknownTransportOutcomePersistsAskAndNeverRedispatchesAfterRestart() throws Exception {
        Fixture fixture=new Fixture(); Proposal proposal=proposal(fixture);
        try (var executor=executor(root)) {
            var result=execute(executor,"inc-unknown",proposal,RepairAuthorization.Automatic.INSTANCE,fixture,() -> policy(),(a,p) -> {
                fixture.writes.incrementAndGet(); throw new java.io.IOException("response lost after send");
            });
            assertThat(result.status()).isEqualTo(ManagedRepairExecutor.Status.OUTCOME_UNKNOWN);
            assertThat(result.attemptState()).isEqualTo(AttemptState.OUTCOME_UNKNOWN);
            assertThat(executor.controlStore().degradation(app.id())).isPresent();
        }
        try (var reopened=executor(root)) {
            reopened.recover(app);
            var result=execute(reopened,"inc-after-restart",proposal,RepairAuthorization.Automatic.INSTANCE,fixture,() -> policy(),fixture::repair);
            assertThat(result.status()).isEqualTo(ManagedRepairExecutor.Status.REQUIRES_APPROVAL);
            assertThat(fixture.writes).hasValue(1);
            assertThat(reopened.attempts().nonTerminal()).singleElement().extracting(ActionAttempt::state).isEqualTo(AttemptState.OUTCOME_UNKNOWN);
        }
    }

    @Test void exitSuccessWithoutBusinessRecoveryFailsVerificationAndDegrades() throws Exception {
        Fixture fixture=new Fixture(); Proposal proposal=proposal(fixture);
        try (var executor=executor(root)) {
            var result=execute(executor,"inc-failed",proposal,RepairAuthorization.Automatic.INSTANCE,fixture,() -> policy(),(a,p) -> {
                fixture.writes.incrementAndGet(); fixture.running=true;
                return new ManagedFixAdapter.ExecutionReport(EffectCertainty.EFFECT_CONFIRMED,"command exited zero but persistent business fault remains");
            });
            assertThat(result.status()).isEqualTo(ManagedRepairExecutor.Status.VERIFICATION_FAILED);
            assertThat(result.attemptState()).isEqualTo(AttemptState.ESCALATED);
            assertThat(result.verification().recovered()).isFalse();
            assertThat(executor.controlStore().degradation(app.id())).isPresent();
            assertThat(fixture.writes).hasValue(1);
        }
        try (var reopened=executor(root)) { reopened.recover(app); assertThat(reopened.controlStore().degradation(app.id())).isPresent(); }
    }

    @Test void selfRecoveryAndDependencyFailureDuringPrecheckCauseNoRepair() throws Exception {
        for (boolean dependencyFailed:List.of(false,true)) {
            Fixture fixture=new Fixture(); Proposal proposal=proposal(fixture);
            if (dependencyFailed) fixture.dependencies=false;
            else { fixture.running=true; fixture.healthy=true; }
            try (var executor=executor(root.resolve(dependencyFailed ? "dep" : "recovered"))) {
                var result=execute(executor,"inc-noeffect",proposal,RepairAuthorization.Automatic.INSTANCE,fixture,() -> policy(),fixture::repair);
                assertThat(result.status()).isEqualTo(ManagedRepairExecutor.Status.NO_EFFECT);
                assertThat(fixture.writes).hasValue(0);
                assertThat(result.authorization()).isNull();
            }
        }
    }

    @Test void revokedPolicyDuringSlowProbeIsCheckedAgainBeforeDispatch() throws Exception {
        Fixture fixture=new Fixture(); Proposal proposal=proposal(fixture);
        var live=new AtomicReference<>(policy());
        var observers=(IndependentManagedVerifier.ObserverFactory) () -> (a,p) -> {
            Observation observation=fixture.read(a,p);
            if (p==Probe.DEPENDENCIES) live.set(ActionPolicy.ask(app,clock.instant().plusSeconds(60)));
            return observation;
        };
        try (var executor=executor(root)) {
            var result=executor.execute("inc-revoked",app,proposal.decision(),proposal.evidence(),RepairAuthorization.Automatic.INSTANCE,
                () -> app,live::get,observers,fixture::repair,ExecutionControl.none());
            assertThat(result.status()).isEqualTo(ManagedRepairExecutor.Status.NO_EFFECT);
            assertThat(fixture.writes).hasValue(0);
        }
    }

    @Test void staleHumanApprovalAndObserveModeCannotWrite() throws Exception {
        Fixture fixture=new Fixture(); Proposal proposal=proposal(fixture);
        var approval=RepairAuthorization.approved("inc-human",app,proposal.decision(),proposal.evidence(),"test-user",clock);
        clock.advance(Duration.ofSeconds(91));
        // Fresh proposal after approval expiration; the expired approval must still be rejected.
        Proposal fresh=proposal(fixture);
        try (var executor=executor(root)) {
            var result=execute(executor,"inc-human",fresh,approval,fixture,() -> policy(),fixture::repair);
            assertThat(result.status()).isEqualTo(ManagedRepairExecutor.Status.NO_EFFECT);
            assertThat(fixture.writes).hasValue(0);
            ActionPolicy observe=new ActionPolicy(app.id(),app.version(),1,ActionPolicy.Mode.OBSERVE,ActionPolicy.Qualification.DRAFT,
                Set.of(OpsDecision.Playbook.START_STOPPED_V1),clock.instant().plusSeconds(60),1);
            assertThat(execute(executor,"inc-observe",fresh,RepairAuthorization.Automatic.INSTANCE,fixture,() -> observe,fixture::repair).status())
                .isEqualTo(ManagedRepairExecutor.Status.BLOCKED);
        }
    }

    @Test void crashAfterDurableIntentIsRecoveredAsStickyUnknownEvenWithoutDowngradeFile() throws Exception {
        try (var executor=executor(root)) {
            var descriptor=ManagedContracts.descriptor("inc-crash",app,OpsDecision.Playbook.START_STOPPED_V1);
            var coordinator=new ActionAttemptCoordinator(executor.attempts(),null,null);
            var ticket=coordinator.begin(descriptor,null,"crashed-run",true);
            coordinator.completePrecheck(ticket,true,"fresh precheck"); coordinator.markDispatchIntent(ticket);
        }
        try (var reopened=executor(root)) {
            reopened.recover(app);
            assertThat(reopened.controlStore().degradation(app.id())).isPresent();
            assertThat(reopened.attempts().nonTerminal()).singleElement().extracting(ActionAttempt::state).isEqualTo(AttemptState.OUTCOME_UNKNOWN);
        }
    }

    @Test void malformedPersistentDowngradeBlocksBeforeAnyTransport() throws Exception {
        Fixture fixture=new Fixture(); Proposal proposal=proposal(fixture);
        Files.createDirectories(root.resolve("control")); Files.writeString(root.resolve("control/degraded-demo.json"),"{broken");
        try (var executor=executor(root)) {
            assertThat(execute(executor,"inc-corrupt",proposal,RepairAuthorization.Automatic.INSTANCE,fixture,() -> policy(),fixture::repair).status())
                .isEqualTo(ManagedRepairExecutor.Status.BLOCKED);
            assertThat(fixture.writes).hasValue(0);
        }
    }

    @Test void downgradeProjectionFailureAfterDispatchStillReportsStickyUnknownFromJournal() throws Exception {
        Fixture fixture=new Fixture(); Proposal proposal=proposal(fixture);
        try (var executor=executor(root)) {
            var result=execute(executor,"inc-projection-failed",proposal,RepairAuthorization.Automatic.INSTANCE,fixture,() -> policy(),(a,p) -> {
                fixture.writes.incrementAndGet();
                // Simulate loss of the downgrade projection destination AFTER dispatch intent.
                Files.createDirectory(root.resolve("control/degraded-demo.json"));
                return new ManagedFixAdapter.ExecutionReport(EffectCertainty.EFFECT_UNKNOWN,"transport response lost");
            });
            assertThat(result.status()).isEqualTo(ManagedRepairExecutor.Status.OUTCOME_UNKNOWN);
            assertThat(result.attemptState()).isEqualTo(AttemptState.OUTCOME_UNKNOWN);
            assertThat(executor.attempts().byId(result.attemptId()).orElseThrow().state()).isEqualTo(AttemptState.OUTCOME_UNKNOWN);
            assertThat(fixture.writes).hasValue(1);
        }
    }

    @Test void sustainedVerifierResetsWindowAfterRecurrenceAndCannotReuseOneHealthySample() throws Exception {
        var sample=new AtomicInteger();
        var verifier=new IndependentManagedVerifier(clock,new IndependentManagedVerifier.Settings(5,3,Duration.ofMillis(100)),clock::advance);
        var result=verifier.verify(app,() -> {
            int number=sample.incrementAndGet();
            return (a,p) -> new Observation(a.targetId(),a.composeProject(),a.service(),p,clock.instant(),
                p==Probe.SERVICE ? Status.RUNNING : number==3 ? Status.UNHEALTHY : Status.HEALTHY,"sample="+number);
        });
        assertThat(result.recovered()).isFalse(); assertThat(result.samples()).hasSize(5);
    }

    @Test void verificationStopsOpeningSessionsWhenItsDeadlineExpires() throws Exception {
        var opened=new AtomicInteger();
        var verifier=new IndependentManagedVerifier(clock,
            new IndependentManagedVerifier.Settings(30,3,Duration.ofSeconds(1),Duration.ofSeconds(1)),clock::advance);
        var result=verifier.verify(app,() -> {
            opened.incrementAndGet();
            return (a,p) -> new Observation(a.targetId(),a.composeProject(),a.service(),p,clock.instant(),Status.UNKNOWN,"unavailable");
        });
        assertThat(result.recovered()).isFalse(); assertThat(opened).hasValue(1);
    }

    private ManagedRepairExecutor executor(Path path) throws Exception {
        return new ManagedRepairExecutor(path,clock,new CompositeRunRecorder(),
            new IndependentManagedVerifier(clock,new IndependentManagedVerifier.Settings(3,3,Duration.ofMillis(100)),clock::advance));
    }
    private ActionPolicy policy() {
        return new ActionPolicy(app.id(),app.version(),1,ActionPolicy.Mode.LIMITED_AUTO,ActionPolicy.Qualification.QUALIFIED,
            Set.of(OpsDecision.Playbook.values()),ManagedDecisionTest.NOW.plusSeconds(600),1);
    }
    private record Proposal(OpsDecision decision,List<DecisionEvidence> evidence) {}
    private Proposal proposal(Fixture fixture) throws Exception {
        var ledger=new DecisionEvidenceLedger(app,clock);
        for (Probe p:List.of(Probe.SERVICE,Probe.HEALTH,Probe.BUSINESS,Probe.DEPENDENCIES)) ledger.collect(fixture::read,p);
        var decision=new OpsDecision(OpsDecision.Disposition.PROPOSE_ACTION,"service exited",ledger.snapshot().stream().map(DecisionEvidence::id).toList(),
            OpsDecision.Playbook.START_STOPPED_V1,List.of(),null);
        ledger.submit(decision); return new Proposal(decision,ledger.snapshot());
    }
    private ManagedRepairExecutor.Outcome execute(ManagedRepairExecutor executor,String incident,Proposal proposal,RepairAuthorization.Request request,
            Fixture fixture,java.util.function.Supplier<ActionPolicy> policy,ManagedFixAdapter fix) {
        return executor.execute(incident,app,proposal.decision(),proposal.evidence(),request,() -> app,policy,
            () -> { fixture.opens.incrementAndGet(); return fixture::read; },fix,ExecutionControl.none());
    }
    private final class Fixture {
        boolean running,healthy,dependencies=true;
        final AtomicInteger writes=new AtomicInteger(),opens=new AtomicInteger();
        Observation read(ManagedApplication a,Probe p) {
            return new Observation(a.targetId(),a.composeProject(),a.service(),p,clock.instant(),switch(p) {
                case SERVICE -> running ? Status.RUNNING : Status.STOPPED;
                case DEPENDENCIES -> dependencies ? Status.HEALTHY : Status.UNHEALTHY;
                default -> healthy ? Status.HEALTHY : Status.UNHEALTHY;
            },"normalized fixture");
        }
        ManagedFixAdapter.ExecutionReport repair(ManagedApplication a,OpsDecision.Playbook p) {
            writes.incrementAndGet(); running=true; healthy=true;
            return new ManagedFixAdapter.ExecutionReport(EffectCertainty.EFFECT_CONFIRMED,"transport exit zero");
        }
    }
    private static final class MutableClock extends Clock {
        Instant now=ManagedDecisionTest.NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now=now.plus(duration); }
    }
}
