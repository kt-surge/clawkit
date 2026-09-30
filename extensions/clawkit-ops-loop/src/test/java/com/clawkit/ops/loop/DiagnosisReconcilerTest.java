package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisReconcilerTest {
    private static final Instant NOW = Instant.parse("2026-07-22T00:00:30Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void correctsInconclusiveModelWhenApplicationPoolSaturationIsDirectlyObserved() {
        Diagnosis model = diagnosis("INCONCLUSIVE", Diagnosis.CurrentCondition.ACTIVE);
        DiagnosticSignals signals = new DiagnosticSignals("CONNECTION_EXHAUSTION", "ACTIVE",
            true, false, true, false, false, 10, List.of("metric"));

        Diagnosis result = DiagnosisReconciler.reconcile(model, signals,
            List.of(evidence("metric", EvidenceType.BUSINESS_METRIC)), NOW);

        assertThat(result.rootCauseCode()).isEqualTo("CONNECTION_EXHAUSTION");
        assertThat(result.diagnosisStatus()).isEqualTo(Diagnosis.DiagnosisStatus.CONFIRMED);
        assertThat(result.supportingEvidence()).contains("metric");
        assertThat(result.alternatives()).contains("INCONCLUSIVE");
    }

    @Test
    void preservesAmbiguousRootCauseWhileAddingBaselineEvidenceCoverage() {
        Diagnosis base = diagnosis("INCONCLUSIVE", Diagnosis.CurrentCondition.ACTIVE);
        Diagnosis model = new Diagnosis(base.rootCauseCode(), base.confidence(), List.of(),
            List.of("metric"), base.alternatives(), base.missingEvidence(),
            base.recommendedActionCode(), false, "2", base.diagnosisStatus(),
            base.currentCondition(), NOW, base.resolutionAttribution());
        DiagnosticSignals signals = new DiagnosticSignals("INCONCLUSIVE", "ACTIVE",
            true, false, false, false, false, 5, List.of("metric"));

        Diagnosis result = DiagnosisReconciler.reconcile(model, signals,
            List.of(evidence("metric", EvidenceType.BUSINESS_METRIC)), NOW);

        assertThat(result.rootCauseCode()).isEqualTo("INCONCLUSIVE");
        assertThat(result.supportingEvidence()).contains("metric");
        assertThat(result.contradictingEvidence()).doesNotContain("metric");
    }

    @Test
    void deterministicSignalsOverrideAConfidentConflictingModelConclusion() {
        Diagnosis model = new Diagnosis("APP_DOWN", 0.98, List.of("model-evidence"), List.of(), List.of(),
            List.of(), "RESTART_SERVICE", false, "2", Diagnosis.DiagnosisStatus.CONFIRMED,
            Diagnosis.CurrentCondition.ACTIVE, NOW, Diagnosis.ResolutionAttribution.NONE);
        DiagnosticSignals signals = new DiagnosticSignals("DB_LOCK_WAIT", "ACTIVE",
            false, true, false, false, false, 4, List.of("db-lock"));

        Diagnosis result = DiagnosisReconciler.reconcile(model, signals,
            List.of(evidence("db-lock", EvidenceType.DB_LOCK_GRAPH)), NOW);

        assertThat(result.rootCauseCode()).isEqualTo("DB_LOCK_WAIT");
        assertThat(result.diagnosisStatus()).isEqualTo(Diagnosis.DiagnosisStatus.CONFIRMED);
        assertThat(result.alternatives()).contains("APP_DOWN");
        assertThat(result.recommendedActionCode()).isEqualTo("ESCALATE");

        DiagnosisProvenance provenance = DiagnosisProvenance.modelReconciled(model, result, signals);
        assertThat(provenance.deterministicEvidenceChangedConclusion()).isTrue();
        assertThat(provenance.modelCandidateRootCause()).isEqualTo("APP_DOWN");
        assertThat(provenance.finalRootCause()).isEqualTo("DB_LOCK_WAIT");
        assertThat(provenance.deterministicEvidenceIds()).containsExactly("db-lock");
        assertThat(provenance.reasonCode()).isEqualTo("CURRENT_EVIDENCE_OVERRIDE");
    }

    @Test
    void providerUnavailableCandidateStillUsesCurrentAppDownEvidenceConsistently() {
        var serviceFact = JSON.createObjectNode().put("success", true);
        serviceFact.set("data", JSON.createObjectNode().put("State", "exited"));
        var httpFact = JSON.createObjectNode().put("success", true);
        httpFact.set("data", JSON.createObjectNode().put("statusCode", 503));
        Evidence service = fixtureEvidence("service", EvidenceType.SERVICE_STATUS, serviceFact,
            "order-api");
        Evidence http = fixtureEvidence("http", EvidenceType.HTTP_PROBE, httpFact, "order-api");
        DiscoveryResult discovery = new DiscoveryResult("incident", "run", "APP_DOWN_V1",
            new EvidenceBundle("incident", "run", NOW, List.of(service, http)),
            DiscoveryStatus.COMPLETE, 2, 2, NOW);

        ReconciledDiagnosis result = ReconciledDiagnosis.fromCandidate(
            diagnosis("INCONCLUSIVE", Diagnosis.CurrentCondition.UNKNOWN), discovery, NOW);

        assertThat(result.signals().candidateRootCause()).isEqualTo("APP_DOWN");
        assertThat(result.diagnosis().rootCauseCode()).isEqualTo("APP_DOWN");
        assertThat(result.diagnosis().diagnosisStatus())
            .isEqualTo(Diagnosis.DiagnosisStatus.CONFIRMED);
        assertThat(DiagnosisProvenance.signalsOnly(result.diagnosis(), result.signals(),
            "PROVIDER_NOT_CONFIGURED").deterministicEvidenceIds())
            .containsExactlyInAnyOrder("service", "http");
    }

    private static Diagnosis diagnosis(String root, Diagnosis.CurrentCondition condition) {
        return new Diagnosis(root, 0.5, List.of(), List.of(), List.of(), List.of(),
            "ESCALATE", false, "2", Diagnosis.DiagnosisStatus.INCONCLUSIVE,
            condition, NOW, Diagnosis.ResolutionAttribution.NONE);
    }

    private static Evidence evidence(String id, EvidenceType type) {
        var fact = JSON.createObjectNode().put("success", true);
        fact.set("data", JSON.createObjectNode());
        return fixtureEvidence(id, type, fact);
    }

    private static Evidence fixtureEvidence(String id, EvidenceType type,
                                            com.fasterxml.jackson.databind.node.ObjectNode fact) {
        return fixtureEvidence(id, type, fact, "scope");
    }

    private static Evidence fixtureEvidence(String id, EvidenceType type,
                                            com.fasterxml.jackson.databind.node.ObjectNode fact,
                                            String scope) {
        return new Evidence(id, "incident", type, "test", NOW.minusSeconds(5),
            NOW.minusSeconds(5), scope, Evidence.Kind.FACT, fact,
            "run://baseline-incident/tool/" + id, Evidence.Freshness.CURRENT,
            Evidence.Redaction.NONE, "2", Evidence.CollectionStatus.OBSERVED,
            NOW.plusSeconds(60), null);
    }
}
