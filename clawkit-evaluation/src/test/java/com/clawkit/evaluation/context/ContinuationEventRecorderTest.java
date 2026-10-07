package com.clawkit.evaluation.context;

import com.clawkit.observability.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class ContinuationEventRecorderTest {
    @Test void preparatoryProviderRootsAndVerificationRootsDoNotReplaceTheTaskRoot() {
        var capture = new ContinuationEventRecorder((payload, run, parent, turn, time) -> {});
        var time = Instant.EPOCH;
        capture.record(new RunCompletedPayload(RunStatus.COMPLETED, null, null), "ingestion-only", null, 0, time);
        assertThat(capture.taskRoot()).isNull();
        var started = new RunStartedPayload("synthetic", "workspace", "fixture", "AUTO", "OFF", "REACT");
        capture.record(started, "task", null, 1, time);
        capture.record(started, "independent-verifier", null, 1, time);
        capture.record(new RunCompletedPayload(RunStatus.COMPLETED, null, null), "independent-verifier", null, 1, time);
        assertThat(capture.taskStatus()).isEqualTo("INCOMPLETE");
        capture.record(new RunCompletedPayload(RunStatus.BUDGET_EXHAUSTED, null, null), "task", null, 1, time);
        assertThat(capture.taskRoot()).isEqualTo("task");
        assertThat(capture.taskStatus()).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(capture.runIds()).containsExactly("task", "independent-verifier");
    }
}
