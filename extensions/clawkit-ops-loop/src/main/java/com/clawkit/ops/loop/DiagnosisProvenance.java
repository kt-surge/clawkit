package com.clawkit.ops.loop;

import java.util.List;

/**
 * Minimal, safe-to-persist explanation of how a diagnosis was selected.
 *
 * <p>This deliberately stores only taxonomy codes and evidence identifiers.
 * It never stores model prompts, free-form model output, credentials, host
 * details, or raw log lines.
 */
public record DiagnosisProvenance(
    String mode,
    String modelCandidateRootCause,
    String finalRootCause,
    boolean deterministicEvidenceChangedConclusion,
    List<String> deterministicEvidenceIds,
    String reasonCode
) {
    public static final String MODEL_RECONCILED = "MODEL_RECONCILED";
    public static final String SIGNALS_ONLY = "SIGNALS_ONLY";
    public static final String NOT_RECORDED = "NOT_RECORDED";

    public DiagnosisProvenance {
        mode = require(mode, "mode");
        finalRootCause = require(finalRootCause, "finalRootCause");
        deterministicEvidenceIds = deterministicEvidenceIds == null
            ? List.of() : List.copyOf(deterministicEvidenceIds);
        reasonCode = require(reasonCode, "reasonCode");
        if (!List.of(MODEL_RECONCILED, SIGNALS_ONLY, NOT_RECORDED).contains(mode)) {
            throw new IllegalArgumentException("unsupported provenance mode");
        }
        if (!taxonomyCode(finalRootCause)) {
            throw new IllegalArgumentException("finalRootCause must be a taxonomy code");
        }
        if (modelCandidateRootCause != null && !taxonomyCode(modelCandidateRootCause)) {
            throw new IllegalArgumentException("modelCandidateRootCause must be a taxonomy code");
        }
        if (!taxonomyCode(reasonCode)) {
            throw new IllegalArgumentException("reasonCode must be a controlled code");
        }
        if (deterministicEvidenceIds.stream().anyMatch(id -> id == null
            || !id.matches("[A-Za-z0-9_-]{1,128}"))) {
            throw new IllegalArgumentException("deterministicEvidenceIds must be safe identifiers");
        }
    }

    public static DiagnosisProvenance modelReconciled(Diagnosis model,
                                                       Diagnosis reconciled,
                                                       DiagnosticSignals signals) {
        boolean changed = !model.rootCauseCode().equals(reconciled.rootCauseCode());
        return new DiagnosisProvenance(MODEL_RECONCILED, model.rootCauseCode(),
            reconciled.rootCauseCode(), changed, signals.relevantEvidence(),
            changed ? "CURRENT_EVIDENCE_OVERRIDE" : "CURRENT_EVIDENCE_CONFIRMED");
    }

    public static DiagnosisProvenance signalsOnly(Diagnosis diagnosis,
                                                   DiagnosticSignals signals,
                                                   String reasonCode) {
        return new DiagnosisProvenance(SIGNALS_ONLY, null, diagnosis.rootCauseCode(), false,
            signals.relevantEvidence(), reasonCode);
    }

    public static DiagnosisProvenance notRecorded(Diagnosis diagnosis) {
        return new DiagnosisProvenance(NOT_RECORDED, null, diagnosis.rootCauseCode(), false,
            List.of(), "LEGACY_ARTIFACT");
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " required");
        }
        return value;
    }

    private static boolean taxonomyCode(String value) {
        return value.matches("[A-Z_]+");
    }
}
