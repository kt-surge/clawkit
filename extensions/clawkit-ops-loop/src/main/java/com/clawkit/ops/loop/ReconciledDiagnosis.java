package com.clawkit.ops.loop;

import java.time.Instant;
import java.util.List;

/**
 * Shared deterministic reconciliation result for every diagnosis entry point.
 *
 * <p>The candidate may come from a provider or be the explicit INCONCLUSIVE
 * fallback when no provider is available. In both cases only current,
 * successfully collected evidence may alter the final diagnosis.
 */
public record ReconciledDiagnosis(Diagnosis diagnosis, DiagnosticSignals signals) {
    public ReconciledDiagnosis {
        if (diagnosis == null) throw new IllegalArgumentException("diagnosis required");
        if (signals == null) throw new IllegalArgumentException("signals required");
    }

    public static ReconciledDiagnosis fromCandidate(Diagnosis candidate,
                                                     DiscoveryResult discovery,
                                                     Instant evaluatedAt) {
        if (candidate == null) throw new IllegalArgumentException("candidate required");
        if (discovery == null) throw new IllegalArgumentException("discovery required");
        if (evaluatedAt == null) throw new IllegalArgumentException("evaluatedAt required");

        List<Evidence> currentEvidence = discovery.bundle().evidence().stream()
            .filter(e -> e.collectionStatus() == Evidence.CollectionStatus.OBSERVED)
            .filter(e -> e.freshness() == Evidence.Freshness.CURRENT)
            .filter(e -> e.fact().path("success").asBoolean(false))
            .filter(e -> e.isCurrentAt(evaluatedAt))
            .toList();
        DiagnosticSignals signals = DiagnosticSignals.extract(currentEvidence);
        return new ReconciledDiagnosis(DiagnosisReconciler.reconcile(candidate, signals,
            currentEvidence, evaluatedAt), signals);
    }
}
