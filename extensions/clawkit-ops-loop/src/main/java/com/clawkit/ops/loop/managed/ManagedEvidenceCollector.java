package com.clawkit.ops.loop.managed;

import java.time.Clock;
import java.util.List;

/** Initial discovery facts, not a diagnosis. The Agent chooses additional probes from this same ledger. */
public final class ManagedEvidenceCollector {
    private ManagedEvidenceCollector() {}
    public static List<DecisionEvidence> initial(ManagedApplication app,ManagedObserver observer,Clock clock) throws Exception {
        var ledger=new DecisionEvidenceLedger(app,clock);
        for (var probe:List.of(ManagedObserver.Probe.SERVICE,ManagedObserver.Probe.HEALTH,ManagedObserver.Probe.BUSINESS,ManagedObserver.Probe.DEPENDENCIES)) {
            try { ledger.collect(observer,probe); }
            catch (Exception e) {
                if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); throw e; }
                var at=clock.instant();
                ledger.collect((a,p) -> new ManagedObserver.Observation(a.targetId(),a.composeProject(),a.service(),p,at,ManagedObserver.Status.UNKNOWN,
                    "initial probe unavailable: "+e.getClass().getSimpleName(),EvidenceEnvelope.Payload.snapshot(EvidenceEnvelope.Source.DOCKER_INSPECT,
                        EvidenceEnvelope.Quality.ERROR,at,"initial observation failed; source facts unavailable")),probe);
            }
        }
        return ledger.snapshot();
    }
}
