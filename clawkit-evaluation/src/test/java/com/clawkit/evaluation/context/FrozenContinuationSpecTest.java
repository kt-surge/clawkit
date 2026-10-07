package com.clawkit.evaluation.context;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FrozenContinuationSpecTest {
    @Test void twentyFourTasksHaveThreeRepeatsAndExactlyTwoHundredSixteenBalancedInstances() throws Exception {
        var input = data(); var spec = ContinuationSpec.parseFrozen(input);
        assertThatThrownBy(() -> ContinuationSpec.parse(input)).isInstanceOf(IllegalArgumentException.class);
        assertThat(spec.tasks()).hasSize(24); assertThat(spec.instances()).hasSize(216).isEqualTo(spec.instances());
        assertThat(spec.instances().stream().map(ContinuationSpec.Instance::id).distinct()).hasSize(216);
        for (var task : spec.tasks()) {
            var rows = spec.instances().stream().filter(i -> i.taskId().equals(task.id())).toList();
            assertThat(rows).hasSize(9);
            for (var arm : ContinuationSpec.arms(task.suite(), spec.split()))
                assertThat(rows.stream().filter(i -> i.arm() == arm).map(ContinuationSpec.Instance::repetition))
                    .containsExactlyInAnyOrder(1, 2, 3);
            var agent = EvaluationArtifacts.JSON.valueToTree(task.agentInput());
            for (String reserved : List.of("gold", "family", "factSources", "workspaceEvidenceSources", "additionalJsonFiles"))
                assertThat(agent.has(reserved)).isFalse();
        }
        for (var suite : ContinuationSpec.Suite.values()) for (int rep = 1; rep <= 3; rep++) {
            final int current = rep;
            var rows = spec.instances().stream().filter(i -> i.suite() == suite && i.repetition() == current).toList();
            for (var arm : ContinuationSpec.arms(suite, spec.split()))
                assertThat(java.util.stream.IntStream.range(0, 12).filter(i -> rows.get(i * 3).arm() == arm).count()).isEqualTo(4);
        }
    }
    @Test void budgetVariantOrSourceChangesCannotMasqueradeAsTheFrozenContract() throws Exception {
        var changed = data(); ((ObjectNode) changed.path("limitsDraft")).put("instanceProviderCalls", 13).put("totalProviderCalls", 2808);
        var wrongBudget = changed;
        assertThatThrownBy(() -> ContinuationSpec.parseFrozen(wrongBudget)).isInstanceOf(IllegalArgumentException.class);
        changed = data(); ((ObjectNode) changed.path("suites")).putArray("COMPRESSION").add("C1_ROLLING_SUMMARY")
            .add("C2_CURRENT_LADDER_GENERAL").add("C3_CANDIDATE_CONTEXT");
        var wrongArms = changed;
        assertThatThrownBy(() -> ContinuationSpec.parseFrozen(wrongArms)).isInstanceOf(IllegalArgumentException.class);
        var wrongFresh = data(); var fresh = java.util.stream.StreamSupport.stream(wrongFresh.path("tasks").spliterator(), false)
            .filter(t -> t.path("id").asText().equals("history-health-requires-freshness")).findFirst().orElseThrow();
        ((ObjectNode) fresh.path("gold").path("workspaceEvidenceSources")).put("status-current-probe", "../outside.json");
        assertThatThrownBy(() -> ContinuationSpec.parseFrozen(wrongFresh)).isInstanceOf(IllegalArgumentException.class);
    }
    private static com.fasterxml.jackson.databind.JsonNode data() throws Exception {
        return EvaluationArtifacts.JSON.readTree(Files.readAllBytes(EvaluationSourceSnapshot.repository().resolve("benchmarks/context-memory-heldout-v1.json")));
    }
}
