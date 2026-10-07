package com.clawkit.evaluation.context;

import com.clawkit.context.*;
import java.util.Objects;

/** Makes context budgeting use the same tool definitions that the evaluation provider actually receives. */
public final class FixedToolContextPipeline implements ContextPipeline {
    private final ContextPipeline delegate;
    private final Tokenizer tokenizer;
    private int toolTokens;
    private final int outputReserve;

    public FixedToolContextPipeline(ContextPipeline delegate, Tokenizer tokenizer) {
        this(delegate, tokenizer, 0);
    }
    public FixedToolContextPipeline(ContextPipeline delegate, Tokenizer tokenizer, int outputReserve) {
        this.delegate = Objects.requireNonNull(delegate);
        this.tokenizer = Objects.requireNonNull(tokenizer);
        this.outputReserve = outputReserve;
    }

    @Override public ModelContext build(ContextRequest request) {
        var tools = FixedToolEvaluationGateway.filter(request.tools());
        toolTokens = 0;
        for (var tool : tools) {
            if (tool.inputSchema() != null) toolTokens += tokenizer.countTokens(tool.inputSchema().toString());
            toolTokens += tokenizer.countTokens(tool.name() == null ? "" : tool.name());
            toolTokens += tokenizer.countTokens(tool.description() == null ? "" : tool.description());
        }
        return delegate.build(new ContextRequest(request.systemPrompt(), request.sessionHistory(), request.workspaceContext(),
            request.runtimeContext(), request.memoryContext(), request.skillContext(), tools, request.budget()));
    }

    @Override public CompactionResult compact(CompactionRequest request) {
        return delegate.compact(new CompactionRequest(request.modelContext(), toolTokens, request.turnCount(), request.hint(),
            Math.max(outputReserve, request.reservedOutputTokens()), request.safetyMarginTokens(), request.runTokenBudgetRemaining()));
    }
}
