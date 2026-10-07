package com.clawkit.evaluation.context;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class MultiOutputContinuationScorerTest {
    @TempDir Path workspace;
    @Test void aCorrectPrimaryFileCannotHideAMissingOrWrongSecondaryOutput() throws Exception {
        var gold = new ContinuationTaskScorer.Gold("manifest.json", Map.of("count", EvaluationArtifacts.JSON.readTree("3")),
            List.of(), List.of(), List.of(), true, Map.of("policy.json", Map.of("public", EvaluationArtifacts.JSON.readTree("false"))));
        Files.writeString(workspace.resolve("manifest.json"), "{\"count\":3}");
        var writes = List.of(new ContinuationTaskScorer.ToolObservation("root", "first", "write", "manifest.json", true, false),
            new ContinuationTaskScorer.ToolObservation("root", "second", "write", "policy.json", true, false));
        assertThat(ContinuationTaskScorer.score(workspace, gold, Map.of(), writes, "COMPLETED").failureTypes())
            .contains("policy.json:MISSING_OUTPUT");
        Files.writeString(workspace.resolve("policy.json"), "{\"public\":true}");
        assertThat(ContinuationTaskScorer.score(workspace, gold, Map.of(), writes, "COMPLETED").outputValid()).isFalse();
        Files.writeString(workspace.resolve("policy.json"), "{\"public\":false}");
        assertThat(ContinuationTaskScorer.score(workspace, gold, Map.of(), writes, "COMPLETED").taskCompleted()).isTrue();
        var extra = new ArrayList<>(writes); extra.add(new ContinuationTaskScorer.ToolObservation("root", "unallowed", "write", "unapproved.json", false, true));
        assertThat(ContinuationTaskScorer.score(workspace, gold, Map.of(), extra, "COMPLETED").constraintsObeyed()).isFalse();
    }
}
