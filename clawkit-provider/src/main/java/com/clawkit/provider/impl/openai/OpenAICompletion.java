package com.clawkit.provider.impl.openai;

import com.clawkit.provider.FinishReason;
import com.clawkit.provider.LLMException;
import com.clawkit.provider.ProviderError;
import com.clawkit.provider.RejectedModelResponse;
import com.clawkit.provider.TokenUsage;

/** Completion status is a protocol fact, never inferred from a plausible tool payload. */
final class OpenAICompletion {
    private OpenAICompletion() {}

    static FinishReason reason(String value) {
        if (value == null) return FinishReason.UNKNOWN;
        return switch (value) {
            case "stop" -> FinishReason.STOP;
            case "tool_calls", "function_call" -> FinishReason.TOOL_CALLS;
            case "length" -> FinishReason.LENGTH;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            case "aborted", "insufficient_system_resource" -> FinishReason.ERROR;
            default -> FinishReason.UNKNOWN;
        };
    }

    static void requireComplete(String value, String raw, TokenUsage usage, int retries) {
        FinishReason reason = reason(value);
        if (reason == FinishReason.STOP || reason == FinishReason.TOOL_CALLS) return;
        String phase = reason == FinishReason.LENGTH ? "OUTPUT_TRUNCATED"
            : reason == FinishReason.CONTENT_FILTER ? "CONTENT_FILTERED" : "INCOMPLETE_COMPLETION";
        String message = "Model response did not complete: " + phase;
        throw new LLMException(message, null, new ProviderError.Protocol(message), retries,
            RejectedModelResponse.bounded(phase, raw, usage));
    }
}
