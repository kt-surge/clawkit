package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureRecommendEvidenceMainTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path output;

    @Test
    void producesSignedEvidenceThatCurrentDeterministicFactOverridesCandidate() throws Exception {
        Path report = FixtureRecommendEvidenceMain.run(output);
        String[] lines = Files.readString(report).replace("\r\n", "\n").split("\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).startsWith("# SHA-256: ");
        JsonNode root = JSON.readTree(lines[0]);
        assertThat(root.path("fixtureOnly").asBoolean()).isTrue();
        assertThat(root.path("providerCandidateSimulated").asBoolean()).isTrue();
        assertThat(root.path("providerCalls").asInt()).isZero();
        JsonNode provenance = root.path("diagnosisProvenance");
        assertThat(provenance.path("modelCandidateRootCause").asText()).isEqualTo("APP_DOWN");
        assertThat(provenance.path("finalRootCause").asText()).isEqualTo("DB_LOCK_WAIT");
        assertThat(provenance.path("deterministicEvidenceChangedConclusion").asBoolean()).isTrue();
        assertThat(provenance.path("deterministicEvidenceIds").get(0).asText()).isEqualTo("fixture-db-lock-1");
    }
}
