package com.clawkit.evaluation.context;

import java.nio.file.Path;
import java.util.UUID;

/** Explicit frozen holdout entry point; prepare is the default and never obtains API credentials. */
public final class ContextMemoryFrozen {
    private ContextMemoryFrozen() {}
    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("context.memory.frozen.mode", "prepare");
        Path output = Path.of(System.getProperty("context.memory.frozen.output", "target/context-memory-frozen-" + UUID.randomUUID()));
        Path dataset = Path.of(System.getProperty("context.memory.frozen.dataset",
            EvaluationSourceSnapshot.repository().resolve("benchmarks/context-memory-heldout-v1.json").toString()));
        ContextMemoryContinuation.run(mode, output, dataset, ContinuationSpec.Split.FROZEN_HOLDOUT);
    }
}
