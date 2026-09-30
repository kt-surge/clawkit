package com.clawkit.ops.delivery;

import com.clawkit.ops.loop.Diagnosis;
import com.clawkit.ops.loop.DiagnosisProvenance;
import com.clawkit.ops.loop.DiscoveryResult;
import com.clawkit.ops.loop.DiscoveryStatus;
import com.clawkit.ops.loop.Evidence;
import com.clawkit.ops.loop.EvidenceBundle;
import com.clawkit.ops.loop.EvidenceType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisReportProvenanceTest {
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void reportsOnlyTaxonomyAndEvidenceCountWhenDeterministicEvidenceOverridesModel() {
        var fact = JSON.createObjectNode().put("success", true);
        fact.set("data", JSON.createObjectNode());
        Evidence evidence = new Evidence("db-lock-1", "inc-fixture", EvidenceType.DB_LOCK_GRAPH,
            "fixture://db-lock", NOW, NOW, "order-api", Evidence.Kind.FACT, fact,
            "run://baseline-fixture/db-lock-1", Evidence.Freshness.CURRENT,
            Evidence.Redaction.NONE, "2", Evidence.CollectionStatus.OBSERVED,
            NOW.plusSeconds(60), null);
        DiscoveryResult discovery = new DiscoveryResult("inc-fixture", "run-fixture",
            "APP_DOWN_V1", new EvidenceBundle("inc-fixture", "run-fixture", NOW,
            List.of(evidence)),
            DiscoveryStatus.COMPLETE, 0, 0, NOW);
        Diagnosis diagnosis = new Diagnosis("DB_LOCK_WAIT", 0.9, List.of(), List.of(),
            List.of("APP_DOWN"), List.of(), "ESCALATE", false);
        DiagnosisProvenance provenance = new DiagnosisProvenance(
            DiagnosisProvenance.MODEL_RECONCILED, "APP_DOWN", "DB_LOCK_WAIT", true,
            List.of("db-lock-1", "metric-1"), "CURRENT_EVIDENCE_OVERRIDE");

        String report = OpsInvestigationFacade.renderReport("fixture-a", "order-api", discovery,
            diagnosis, provenance, UserIncidentStatus.INCONCLUSIVE);

        assertThat(report).contains("模型候选 APP_DOWN");
        assertThat(report).contains("2 项确定性证据修正为 DB_LOCK_WAIT");
        assertThat(report).doesNotContain("CURRENT_EVIDENCE_OVERRIDE");
    }

    @Test
    void reportsSignalsOnlyWhenProviderWasNotUsed() {
        DiscoveryResult discovery = discovery();
        Diagnosis diagnosis = new Diagnosis("APP_DOWN", 0.95, List.of("service-1"), List.of(),
            List.of("INCONCLUSIVE"), List.of(), "ESCALATE", false);
        DiagnosisProvenance provenance = new DiagnosisProvenance(
            DiagnosisProvenance.SIGNALS_ONLY, null, "APP_DOWN", false,
            List.of("service-1", "http-1"), "PROVIDER_NOT_CONFIGURED");

        String report = OpsInvestigationFacade.renderReport("fixture-a", "order-api", discovery,
            diagnosis, provenance, UserIncidentStatus.INCONCLUSIVE);

        assertThat(report).contains("未调用 Provider（PROVIDER_NOT_CONFIGURED）");
        assertThat(report).contains("结论仅来自当前确定性证据");
    }

    private static DiscoveryResult discovery() {
        var fact = JSON.createObjectNode().put("success", true);
        fact.set("data", JSON.createObjectNode());
        Evidence evidence = new Evidence("db-lock-1", "inc-fixture", EvidenceType.DB_LOCK_GRAPH,
            "fixture://db-lock", NOW, NOW, "order-api", Evidence.Kind.FACT, fact,
            "run://baseline-fixture/db-lock-1", Evidence.Freshness.CURRENT,
            Evidence.Redaction.NONE, "2", Evidence.CollectionStatus.OBSERVED,
            NOW.plusSeconds(60), null);
        return new DiscoveryResult("inc-fixture", "run-fixture",
            "APP_DOWN_V1", new EvidenceBundle("inc-fixture", "run-fixture", NOW,
            List.of(evidence)), DiscoveryStatus.COMPLETE, 0, 0, NOW);
    }
}
