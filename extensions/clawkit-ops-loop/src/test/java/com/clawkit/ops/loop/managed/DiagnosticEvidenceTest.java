package com.clawkit.ops.loop.managed;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;
import static org.assertj.core.api.Assertions.*;

class DiagnosticEvidenceTest {
    @TempDir Path root;
    static final Instant NOW=ManagedDecisionTest.NOW;
    static final Clock CLOCK=ManagedDecisionTest.CLOCK;
    @Test void inventedStaleMissingAndConflictingFactsCannotSupportADiagnosis() throws Exception {
        var app=ManagedDecisionTest.app(); var ledger=new DecisionEvidenceLedger(app,CLOCK);
        var fresh=ledger.collect(observer(NOW,Status.UNHEALTHY,EvidenceEnvelope.Quality.COMPLETE),Probe.DEPENDENCIES);
        var stale=ledger.collect(observer(NOW.minusSeconds(100),Status.UNHEALTHY,EvidenceEnvelope.Quality.COMPLETE),Probe.DEPENDENCIES);
        var missing=ledger.collect(observer(NOW,Status.UNKNOWN,EvidenceEnvelope.Quality.MISSING),Probe.LOGS);
        assertThatThrownBy(() -> report("made-up",List.of()).validate(ledger.snapshot(),CLOCK)).hasMessageContaining("unknown diagnostic");
        assertThatThrownBy(() -> report(stale.id(),List.of()).validate(ledger.snapshot(),CLOCK)).hasMessageContaining("stale");
        assertThatThrownBy(() -> report(missing.id(),List.of()).validate(ledger.snapshot(),CLOCK)).hasMessageContaining("incomplete");
        assertThatThrownBy(() -> report(missing.id(),List.of()).validate(ledger.snapshot(),CLOCK))
            .hasMessageContaining(missing.id()).hasMessageContaining("missingEvidence").hasMessageContaining("MISSING");
        var healthy=ledger.collect(observer(NOW,Status.HEALTHY,EvidenceEnvelope.Quality.COMPLETE),Probe.DEPENDENCIES);
        assertThatThrownBy(() -> report(fresh.id(),List.of()).validate(ledger.snapshot(),CLOCK)).hasMessageContaining("conflicting");
        report(fresh.id(),List.of(healthy.id())).validate(ledger.snapshot(),CLOCK);
        assertThat(fresh.envelope().contentHash()).hasSize(64);
    }
    @Test void sourceIdentityFutureTimeAndTemporalCorrelationAreChecked() throws Exception {
        var app=ManagedDecisionTest.app(); var ledger=new DecisionEvidenceLedger(app,CLOCK);
        var change=new EvidenceEnvelope.ChangeRecord("release-2",app.id(),app.version(),app.composeProject(),app.service(),NOW,"v2","updated schema","human");
        var payload=new EvidenceEnvelope.Payload(new EvidenceEnvelope.Collection(EvidenceEnvelope.Source.CHANGE_IMPORT,EvidenceEnvelope.Quality.COMPLETE,
            NOW,NOW,NOW,"records","snapshot","operator assertion",null),null,List.of(change),List.of());
        var ev=ledger.collect((a,p) -> new Observation(a.targetId(),a.composeProject(),a.service(),p,NOW,Status.UNKNOWN,"change",payload),Probe.CHANGES);
        var hypothesis=new DiagnosticReport.Hypothesis("h-config",DiagnosticReport.Cause.CONFIGURATION_MISMATCH,DiagnosticReport.Assessment.SUPPORTED,
            "timing only",List.of(ev.id()),List.of(),List.of(),List.of(),List.of());
        assertThatThrownBy(() -> new DiagnosticReport("timing",List.of(hypothesis)).validate(ledger.snapshot(),CLOCK)).hasMessageContaining("correlation");
        assertThatThrownBy(() -> ledger.collect(observer(NOW.plusSeconds(1),Status.UNKNOWN,EvidenceEnvelope.Quality.COMPLETE),Probe.LOGS)).hasMessageContaining("future");
        var other=new EvidenceEnvelope.ChangeRecord("release-2",app.id(),app.version(),"other",app.service(),NOW,"v2","wrong scope","human");
        var store=new ManagedChangeStore(root,CLOCK);
        assertThatThrownBy(() -> store.importChange(app,other)).hasMessageContaining("match registered");
        store.importChange(app,change); store.importChange(app,change);
        assertThat(store.within(app,NOW.minusSeconds(1),NOW)).containsExactly(change);
        assertThatThrownBy(() -> store.importChange(app,new EvidenceEnvelope.ChangeRecord(change.id(),app.id(),app.version(),app.composeProject(),app.service(),
            NOW,"v3","different payload","human"))).hasMessageContaining("different content");
    }
    @Test void truncatedFactsAndOomEvidenceCannotBeHiddenByOmittingTheirReferences() throws Exception {
        var app=ManagedDecisionTest.app(); var ledger=new DecisionEvidenceLedger(app,CLOCK);
        var refs=new ArrayList<String>();
        for (var p:List.of(Probe.SERVICE,Probe.HEALTH,Probe.BUSINESS,Probe.DEPENDENCIES)) refs.add(ledger.collect(ManagedDecisionTest.observer(Status.STOPPED),p).id());
        ledger.collect(observer(NOW,Status.UNKNOWN,EvidenceEnvelope.Quality.TRUNCATED),Probe.LOGS);
        var proposal=new OpsDecision(OpsDecision.Disposition.PROPOSE_ACTION,"candidate",refs,OpsDecision.Playbook.START_STOPPED_V1,List.of(),null);
        assertThatThrownBy(() -> ledger.submit(proposal)).hasMessageContaining("truncated");
        var oom=new DecisionEvidenceLedger(app,CLOCK);
        var oomRefs=new ArrayList<String>();
        for (var p:List.of(Probe.SERVICE,Probe.HEALTH,Probe.BUSINESS,Probe.DEPENDENCIES)) oomRefs.add(oom.collect(ManagedDecisionTest.observer(Status.STOPPED),p).id());
        oom.collect((a,p) -> new Observation(a.targetId(),a.composeProject(),a.service(),p,NOW,Status.UNKNOWN,"OOMKilled fact",
            new EvidenceEnvelope.Payload(EvidenceEnvelope.Payload.snapshot(EvidenceEnvelope.Source.DOCKER_INSPECT,EvidenceEnvelope.Quality.COMPLETE,NOW,"snapshot").collection(),
                new EvidenceEnvelope.ResourceFacts(true,137,NOW,null,128L),List.of(),List.of())),Probe.RESOURCES);
        assertThatThrownBy(() -> oom.submit(new OpsDecision(OpsDecision.Disposition.PROPOSE_ACTION,"candidate",oomRefs,
            OpsDecision.Playbook.START_STOPPED_V1,List.of(),null))).hasMessageContaining("OOM cause");
    }
    @Test void legacyEvidenceIsStillReadableButHasNoInventedSourceMetadata() {
        var old=new Observation("local-isolated","clawkit-autonomy-test","demo-api",Probe.LOGS,NOW,Status.UNKNOWN,"legacy");
        var ev=new DecisionEvidence("ev-old","demo",1,old,NOW.plusSeconds(90));
        assertThat(ev.envelope()).isNull(); assertThat(old.payload()).isNull();
    }
    @Test void hypothesisLabelsAreNotFilesystemIdentifiersAndOneRecordMayContainContraryFacts() throws Exception {
        var ledger=new DecisionEvidenceLedger(ManagedDecisionTest.app(),CLOCK);
        var fact=ledger.collect(observer(NOW,Status.UNKNOWN,EvidenceEnvelope.Quality.COMPLETE),Probe.RESOURCES);
        var h=new DiagnosticReport.Hypothesis("H1",DiagnosticReport.Cause.RESOURCE_EXHAUSTION,DiagnosticReport.Assessment.UNKNOWN,
            "finite memory limit is a clue; current low usage is counterevidence; no historical trend is available",
            List.of(fact.id()),List.of(fact.id()),List.of("memory history"),List.of(),List.of());
        new DiagnosticReport("uncertain resource cause",List.of(h)).validate(ledger.snapshot(),CLOCK);
        assertThatThrownBy(() -> new DiagnosticReport.Hypothesis("../escape",h.cause(),h.assessment(),h.explanation(),h.supportRefs(),h.counterRefs(),h.missingEvidence(),h.alternativeIds(),h.nextProbes()))
            .isInstanceOf(DiagnosticReport.ContractViolation.class);
    }
    private static ManagedObserver observer(Instant at,Status status,EvidenceEnvelope.Quality quality) {
        return (a,p) -> new Observation(a.targetId(),a.composeProject(),a.service(),p,at,status,"normalized facts",
            EvidenceEnvelope.Payload.snapshot(EvidenceEnvelope.Source.DOCKER_INSPECT,quality,at,quality==EvidenceEnvelope.Quality.TRUNCATED ? "cap reached" : "snapshot"));
    }
    private static DiagnosticReport report(String support,List<String> counter) {
        return new DiagnosticReport("dependency investigation",List.of(new DiagnosticReport.Hypothesis("h-dependency",DiagnosticReport.Cause.DEPENDENCY_FAILURE,
            DiagnosticReport.Assessment.SUSPECTED,"dependency symptom",List.of(support),counter,List.of("inspect upstream"),List.of(),List.of(Probe.DEPENDENCIES))));
    }
}
