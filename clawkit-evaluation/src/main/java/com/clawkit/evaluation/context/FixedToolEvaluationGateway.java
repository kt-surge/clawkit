package com.clawkit.evaluation.context;

import com.clawkit.engine.ProviderGateway;
import com.clawkit.engine.RunScope;
import com.clawkit.provider.*;
import com.clawkit.tools.schema.ToolDefinition;
import java.util.List;
import java.util.Set;

/** Evaluation-only tool surface; an unsupported model proposal never reaches the engine's internal tools. */
public final class FixedToolEvaluationGateway implements ProviderGateway {
    public static final Set<String> TASK_TOOLS = Set.of("read", "write", "glob", "grep");
    private final ProviderGateway delegate;
    private final com.clawkit.tools.control.WorkBudget instanceTools;

    public FixedToolEvaluationGateway(ProviderGateway delegate) { this(delegate, null); }
    public FixedToolEvaluationGateway(ProviderGateway delegate, com.clawkit.tools.control.WorkBudget instanceTools) {
        this.delegate = delegate; this.instanceTools = instanceTools;
    }

    public static List<ToolDefinition> filter(List<ToolDefinition> tools) {
        return tools.stream().filter(tool -> TASK_TOOLS.contains(tool.name())).toList();
    }

    @Override public ModelResponse generate(ModelRequest request, RunScope scope) {
        var tools = filter(request.tools());
        var response = delegate.generate(new ModelRequest(request.messages(), tools, request.parameters(), request.control()), scope);
        var offered = tools.stream().map(ToolDefinition::name).collect(java.util.stream.Collectors.toSet());
        if (response.hasToolCalls() && response.toolCalls().stream().anyMatch(call -> !offered.contains(call.name()))) {
            throw rejected("EVALUATION_UNOFFERED_TOOL", response);
        }
        if (response.hasToolCalls() && instanceTools != null) {
            // Reserve a permit for every proposal across all roots. A rejected batch never reaches tool execution;
            // partial reservations stay consumed, so actual executions cannot exceed this conservative ceiling.
            for (var call : response.toolCalls()) if (!instanceTools.tryAcquireToolCall()) {
                throw rejected("EVALUATION_INSTANCE_TOOL_CALL_LIMIT", response);
            }
        }
        return response;
    }

    @Override public ModelResponse generateStream(ModelRequest request, RunScope scope, StreamObserver observer) {
        throw new UnsupportedOperationException("continuation evaluation uses blocking responses");
    }

    private static LLMException rejected(String code, ModelResponse response) {
        try {
            return new LLMException(code, null, null, 0, RejectedModelResponse.bounded("evaluation-tool-surface",
                EvaluationArtifacts.JSON.writeValueAsString(response), response.usage()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { return new LLMException(code, e); }
    }
}
