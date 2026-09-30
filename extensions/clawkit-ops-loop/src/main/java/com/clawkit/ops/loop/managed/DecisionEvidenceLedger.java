package com.clawkit.ops.loop.managed;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;

/** Per-run ledger. Parallel read tools cannot race the terminal submission. */
final class DecisionEvidenceLedger {
    private final ManagedApplication app;
    private final Clock clock;
    private final Map<String, DecisionEvidence> evidence = new LinkedHashMap<>();
    private final List<String> rejections = new ArrayList<>();
    private OpsDecision submitted;
    private DiagnosticReport diagnosis;

    DecisionEvidenceLedger(ManagedApplication app, Clock clock) { this.app = app; this.clock = clock; }

    DecisionEvidenceLedger(ManagedApplication app,Clock clock,List<DecisionEvidence> baseline) {
        this(app,clock);
        validateCollected(app,OpsDecision.systemEscalation("validate controller evidence"),baseline,clock);
        baseline.forEach(item -> evidence.put(item.id(),item));
    }

    synchronized DecisionEvidence collect(ManagedObserver observer, Probe probe) throws Exception {
        if (submitted != null) throw new IllegalArgumentException("decision already submitted");
        Observation observation = observer.observe(app, probe);
        if (!app.targetId().equals(observation.targetId()) || !app.composeProject().equals(observation.composeProject())
                || !app.service().equals(observation.service()) || probe != observation.probe())
            throw new IllegalArgumentException("observer returned a different target/project/service/probe");
        if (observation.observedAt().isAfter(clock.instant()))
            throw new IllegalArgumentException("observation is from the future");
        validateSource(app,observation,clock);
        String id="ev-"+UUID.randomUUID();
        DecisionEvidence item = new DecisionEvidence(id, app.id(), app.version(),
            observation, observation.observedAt().plus(app.evidenceTtl()),EvidenceEnvelope.of(id,app,observation));
        evidence.put(item.id(), item);
        return item;
    }

    synchronized void submit(OpsDecision decision) {
        if (submitted != null) throw new IllegalArgumentException("decision already submitted");
        validate(decision);
        submitted = decision;
    }
    synchronized void diagnose(DiagnosticReport report) {
        if (submitted!=null) throw new IllegalArgumentException("decision already submitted");
        report.validate(snapshot(),clock); diagnosis=report;
    }
    synchronized DiagnosticReport diagnosis() { return diagnosis; }

    synchronized void validate(OpsDecision decision) {
        if (diagnosis!=null) diagnosis.validate(snapshot(),clock);
        validateCollected(app, decision, List.copyOf(evidence.values()), clock);
    }

    static void validateCollected(ManagedApplication app, OpsDecision decision, List<DecisionEvidence> collected, Clock clock) {
        Map<String, DecisionEvidence> evidence = new LinkedHashMap<>();
        for (DecisionEvidence item : collected) {
            Observation o = item.observation();
            validateSource(app,o,clock);
            if (!app.id().equals(item.applicationId()) || app.version() != item.applicationVersion()
                    || !app.targetId().equals(o.targetId()) || !app.composeProject().equals(o.composeProject())
                    || !app.service().equals(o.service()) || o.observedAt().isAfter(clock.instant())
                    || !item.validUntil().equals(o.observedAt().plus(app.evidenceTtl()))
                    || evidence.putIfAbsent(item.id(), item) != null)
                throw new IllegalArgumentException("evidence identity/freshness contract mismatch");
        }
        List<DecisionEvidence> cited = decision.evidenceRefs().stream().map(id -> {
            DecisionEvidence item = evidence.get(id);
            if (item == null) throw new IllegalArgumentException("unknown evidence reference: " + id);
            return item;
        }).toList();
        if (decision.disposition() != OpsDecision.Disposition.PROPOSE_ACTION) return;
        if (!app.repairIntendedAt(clock.instant())) throw new IllegalArgumentException("maintenance/desired state/stateless declaration forbids repair");
        for (DecisionEvidence item:collected) {
            if (!item.currentAt(clock.instant()) || item.observation().payload()==null) continue;
            var payload=item.observation().payload();
            if (payload.collection().quality()==EvidenceEnvelope.Quality.TRUNCATED)
                throw new IllegalArgumentException("truncated current evidence prevents proposed action");
            if (payload.resources()!=null && payload.resources().oomKilled())
                throw new IllegalArgumentException("OOM cause needs human resource remediation before restart");
        }
        // Reject conflicting or missing observations, including uncited counterevidence.
        for (Probe probe : List.of(Probe.SERVICE, Probe.HEALTH, Probe.BUSINESS, Probe.DEPENDENCIES)) {
            List<DecisionEvidence> current = evidence.values().stream()
                .filter(e -> e.observation().probe() == probe && e.currentAt(clock.instant())).toList();
            if (current.isEmpty() || cited.stream().noneMatch(e -> e.observation().probe() == probe && e.currentAt(clock.instant())))
                throw new IllegalArgumentException("missing fresh cited " + probe + " evidence");
            Status expected = switch (probe) {
                case SERVICE -> decision.playbook() == OpsDecision.Playbook.START_STOPPED_V1 ? Status.STOPPED : Status.RUNNING;
                case DEPENDENCIES -> Status.HEALTHY;
                default -> Status.UNHEALTHY;
            };
            if (current.stream().anyMatch(e -> e.observation().status() != expected))
                throw new IllegalArgumentException("conflicting/unknown " + probe + " evidence prevents proposed action");
        }
        if (cited.stream().anyMatch(e -> !e.currentAt(clock.instant())))
            throw new IllegalArgumentException("proposal cites stale evidence");
    }

    private static void validateSource(ManagedApplication app,Observation observation,Clock clock) {
        var payload=observation.payload(); if (payload==null) return; // Legacy facts remain legacy; no source data is invented.
        if (payload.collection().collectedAt().isAfter(clock.instant())
                || payload.changes().stream().anyMatch(c -> !c.matches(app)))
            throw new IllegalArgumentException("source evidence time/identity mismatch");
    }

    synchronized OpsDecision submitted() { return submitted; }
    synchronized List<DecisionEvidence> snapshot() { return List.copyOf(evidence.values()); }
    synchronized void rejected(String message) { rejections.add(message); }
    synchronized List<String> rejections() { return List.copyOf(rejections); }
}
