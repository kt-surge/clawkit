package com.clawkit.ops.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureAskEvidenceMainTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path output;

    @Test
    void producesVerifiedApprovalAndStickyUnknownFixtureEvidenceWithoutRemoteWrites() throws Exception {
        Path report = FixtureAskEvidenceMain.run(output);
        String[] lines = Files.readString(report).replace("\r\n", "\n").split("\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).startsWith("# SHA-256: ");
        JsonNode root = MAPPER.readTree(lines[0]);
        assertThat(root.path("fixtureOnly").asBoolean()).isTrue();
        assertThat(root.path("remoteWrites").asInt()).isZero();
        JsonNode approved = root.path("scenarios").get(0);
        assertThat(approved.path("finalStatus").asText()).isEqualTo("RESOLVED");
        assertThat(approved.path("fixtureFixCalls").asInt()).isEqualTo(1);
        assertThat(approved.path("independentVerification").asBoolean()).isTrue();
        JsonNode unknown = root.path("scenarios").get(1);
        assertThat(unknown.path("finalStatus").asText()).isEqualTo("NEEDS_HUMAN");
        assertThat(unknown.path("fixtureFixCalls").asInt()).isEqualTo(1);
        assertThat(unknown.path("continueFixCallDelta").asInt()).isZero();
        assertThat(unknown.path("outcomeUnknown").asBoolean()).isTrue();
    }
}
