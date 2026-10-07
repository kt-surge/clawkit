package com.clawkit.evaluation.context;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ContinuationSpecTest {
    @Test void sixIndependentDevelopmentTasksRemainEighteenPairedInstancesWithBalancedFirstArms() throws Exception {
        var spec = ContinuationSpec.parse(data());
        assertThat(spec.tasks()).hasSize(6);
        assertThat(spec.instances()).hasSize(18).isEqualTo(spec.instances());
        for (var suite : ContinuationSpec.Suite.values()) {
            var rows = spec.instances().stream().filter(i -> i.suite() == suite).toList();
            assertThat(List.of(rows.get(0).arm(), rows.get(3).arm(), rows.get(6).arm())).containsExactlyInAnyOrderElementsOf(ContinuationSpec.arms(suite));
        }
        for (var task : spec.tasks()) {
            var agent = EvaluationArtifacts.JSON.valueToTree(task.agentInput());
            assertThat(agent.has("gold")).isFalse();
            assertThat(agent.has("suite")).isFalse();
            assertThat(agent.has("evidenceAlternatives")).isFalse();
        }
    }
    @Test void externalEndpointAndWorkspaceEscapeCannotSlipIntoDevelopmentContract() throws Exception {
        var endpoint = data();
        ((ObjectNode) endpoint.path("modelDraft")).put("endpoint", "https://unreviewed.invalid");
        assertThatThrownBy(() -> ContinuationSpec.parse(endpoint)).isInstanceOf(IllegalArgumentException.class);
        var escaped = data();
        ((ObjectNode) escaped.path("tasks").get(0).path("agentInput").path("workspaceFiles")).put("../outside.txt", "bad");
        assertThatThrownBy(() -> ContinuationSpec.parse(escaped)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void malformedOldToolPairCannotBeHiddenByRecentWindowClipping() throws Exception {
        var malformed = data();
        ((ObjectNode) malformed.path("tasks").get(0).path("agentInput").path("histories").get(0).path("messages").get(1))
            .put("role", "tool").put("toolCallId", "orphan");
        assertThatThrownBy(() -> ContinuationSpec.parse(malformed)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void factRubricCannotReferenceUnknownSourcesOrOmitAnExpectedField() throws Exception {
        var malformed = data();
        var task = java.util.stream.StreamSupport.stream(malformed.path("tasks").spliterator(), false)
            .filter(t -> t.path("id").asText().equals("updated-memory")).findFirst().orElseThrow();
        ((ObjectNode) task.path("gold").path("factSources")).putArray("timeoutSeconds").addArray().add("not-an-original-source");
        assertThatThrownBy(() -> ContinuationSpec.parse(malformed)).isInstanceOf(IllegalArgumentException.class);
        ((ObjectNode) task.path("gold").path("factSources")).remove("timeoutSeconds");
        assertThatThrownBy(() -> ContinuationSpec.parse(malformed)).isInstanceOf(IllegalArgumentException.class);
    }
    private static com.fasterxml.jackson.databind.JsonNode data() throws Exception {
        return EvaluationArtifacts.JSON.readTree(Files.readAllBytes(EvaluationSourceSnapshot.repository().resolve("benchmarks/context-memory-dev-v1.json")));
    }
}
