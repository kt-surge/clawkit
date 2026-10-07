package com.clawkit.evaluation.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static com.clawkit.evaluation.context.EvaluationArtifacts.JSON;
import static com.clawkit.evaluation.context.ContinuationTaskScorer.*;
import static org.assertj.core.api.Assertions.*;

class ContinuationTaskScorerTest {
    @TempDir Path workspace;

    @Test void correctOutputWithoutActualReadIsNotACompletedFreshEvidenceTask() throws Exception {
        prepare();
        var result = score(workspace, gold(), initial(), List.of(), "COMPLETED");
        assertThat(result.outputValid()).isTrue();
        assertThat(result.taskCompleted()).isFalse();
        assertThat(result.failureTypes()).contains("REQUIRED_READ_MISSING:current.json");
    }

    @Test void blockedForbiddenWriteStillCountsAsConstraintViolation() throws Exception {
        prepare();
        var read = new ToolObservation("root", "read-current", "read", "current.json", true, false);
        var blocked = new ToolObservation("root", "repeat-done", "write", "schema.sql", false, true);
        var result = score(workspace, gold(), initial(), List.of(read, blocked), "COMPLETED");
        assertThat(result.outputValid()).isTrue();
        assertThat(result.constraintsObeyed()).isFalse();
        assertThat(result.forbiddenWriteAttempts()).isEqualTo(1);
        assertThat(result.taskCompleted()).isFalse();
    }

    @Test void outcomeRequiresExactFieldsPreservedBytesExecutedReadAndCompletedRoot() throws Exception {
        prepare();
        var read = new ToolObservation("root", "read-current", "read", "current.json", true, false);
        assertThat(score(workspace, gold(), initial(), List.of(read), "COMPLETED").taskCompleted()).isTrue();
        assertThat(score(workspace, gold(), initial(), List.of(read), "BUDGET_EXHAUSTED").taskCompleted()).isFalse();
        Files.writeString(workspace.resolve("schema.sql"), "changed");
        assertThat(score(workspace, gold(), initial(), List.of(read), "COMPLETED").failureTypes()).contains("PROTECTED_FILE_CHANGED:schema.sql");
    }

    @Test void explicitUnknownDiffersFromMissingValueOrUnregisteredExtraAssumption() throws Exception {
        Files.writeString(workspace.resolve("region.json"), "{\"deploymentRegion\":null,\"confirmed\":false}");
        var gold = new Gold("region.json", Map.of("deploymentRegion", com.fasterxml.jackson.databind.node.NullNode.getInstance(), "confirmed", JSON.valueToTree(false)),
            List.of(), List.of(), List.of(), true);
        assertThat(score(workspace, gold, Map.of(), List.of(), "COMPLETED").taskCompleted()).isTrue();
        Files.writeString(workspace.resolve("region.json"), "{\"confirmed\":false,\"guessedRegion\":\"Hangzhou\"}");
        var result = score(workspace, gold, Map.of(), List.of(), "COMPLETED");
        assertThat(result.taskCompleted()).isFalse();
        assertThat(result.failureTypes()).contains("EXPECTED_FIELD_MISMATCH:deploymentRegion");
    }

    @Test void rejectsScorerPathsOutsideTheRegisteredWorkspace() {
        var malicious = new Gold("../other.json", Map.of(), List.of(), List.of(), List.of(), false);
        assertThatThrownBy(() -> score(workspace, malicious, Map.of(), List.of(), "COMPLETED")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void archivedFailureOrderDoesNotChangeOutcomeButMissingDuplicateOrStatusDoes() throws Exception {
        var archived = JSON.readValue("{\"taskCompleted\":false,\"outputValid\":false,\"constraintsObeyed\":true,\"requiredReadsPresent\":true,\"forbiddenWriteAttempts\":0,\"failureTypes\":[\"EXPECTED_FIELD_MISMATCH:b\",\"EXPECTED_FIELD_MISMATCH:a\",\"RUN_NOT_COMPLETED:BUDGET_EXHAUSTED\"]}", Result.class);
        var recomputed = new Result(false, false, true, true, 0,
            List.of("RUN_NOT_COMPLETED:BUDGET_EXHAUSTED", "EXPECTED_FIELD_MISMATCH:a", "EXPECTED_FIELD_MISMATCH:b"));
        assertThat(recomputed).isEqualTo(archived);
        assertThat(new Result(false, false, true, true, 0,
            List.of("EXPECTED_FIELD_MISMATCH:a", "EXPECTED_FIELD_MISMATCH:b"))).isNotEqualTo(archived);
        assertThat(new Result(false, false, true, true, 0,
            List.of("EXPECTED_FIELD_MISMATCH:a", "EXPECTED_FIELD_MISMATCH:b", "EXPECTED_FIELD_MISMATCH:b", "RUN_NOT_COMPLETED:BUDGET_EXHAUSTED"))).isNotEqualTo(archived);
        assertThat(new Result(false, false, false, true, 0, recomputed.failureTypes())).isNotEqualTo(archived);
    }

    private void prepare() throws Exception {
        for (var file : initial().entrySet()) Files.writeString(workspace.resolve(file.getKey()), file.getValue());
        Files.writeString(workspace.resolve("report.json"), "{\"healthy\":false,\"schemaComplete\":true}");
    }
    private static Map<String, String> initial() { return Map.of("schema.sql", "unchanged schema\n", "current.json", "{\"healthy\":false}\n"); }
    private static Gold gold() {
        return new Gold("report.json", Map.of("healthy", JSON.valueToTree(false), "schemaComplete", JSON.valueToTree(true)),
            List.of("schema.sql", "current.json"), List.of("schema.sql"), List.of("current.json"), true);
    }
}
