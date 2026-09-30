package com.clawkit.ops.loop.automation;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Optional append-only projection of completed observe-only cycles.
 *
 * <p>The event contains identifiers, evidence references, and a narrowly
 * allow-listed Fixture fact projection only. It must not carry raw tool
 * output, prompts, credentials, or repair parameters.
 */
@FunctionalInterface
public interface ObservationEventSink {

    ObservationEventSink NOOP = event -> { };

    void record(ObservationEvent event) throws IOException;

    record ObservationEvent(
        String targetId,
        String runId,
        String outcome,
        List<String> evidenceRefs,
        List<FixtureEvidenceFact> evidenceFacts,
        boolean diagnosisEnabled,
        boolean providerCalled
    ) {
        public ObservationEvent(
            String targetId, String runId, String outcome, List<String> evidenceRefs,
            boolean diagnosisEnabled, boolean providerCalled
        ) {
            this(targetId, runId, outcome, evidenceRefs, List.of(), diagnosisEnabled, providerCalled);
        }

        public ObservationEvent {
            requireText(targetId, "targetId");
            requireText(runId, "runId");
            requireText(outcome, "outcome");
            evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
            evidenceFacts = List.copyOf(Objects.requireNonNull(evidenceFacts, "evidenceFacts"));
            if (evidenceRefs.stream().anyMatch(ref -> ref == null || ref.isBlank())) {
                throw new IllegalArgumentException("evidenceRefs must not contain blank values");
            }
            for (FixtureEvidenceFact fact : evidenceFacts) {
                if (fact == null || !evidenceRefs.contains(fact.reference())) {
                    throw new IllegalArgumentException("evidenceFacts must belong to evidenceRefs");
                }
            }
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }
}
