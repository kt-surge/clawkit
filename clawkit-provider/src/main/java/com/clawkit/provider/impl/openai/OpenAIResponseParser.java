package com.clawkit.provider.impl.openai;

import com.clawkit.provider.LLMException;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.Role;
import com.clawkit.tools.schema.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 非流式 OpenAI 响应解析器。将 OpenAIResponse → 内部 Message。
 * 纯函数，无副作用，可独立测试。
 */
public class OpenAIResponseParser {

    private final ObjectMapper objectMapper;

    public OpenAIResponseParser() {
        this(new ObjectMapper());
    }

    public OpenAIResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将 OpenAI 非流式响应转换为内部 Message。
     * @throws LLMException 如果响应为空或工具参数 JSON 解析失败
     */
    public Message toMessage(OpenAIResponse response) {
        if (response.choices() == null || response.choices().isEmpty()) {
            throw new LLMException("API 返回了空的 Choices");
        }
        OpenAIChoice choice = response.choices().get(0);
        if (choice == null) throw new LLMException("API response has no choice");
        OpenAIMessage msg = choice.message();
        if (msg == null) throw new LLMException("API response has no assistant message");

        // 工具调用
        if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
            List<ToolCall> toolCalls = new ArrayList<>();
            for (OpenAIToolCall otc : msg.toolCalls()) {
                if (otc == null || otc.function() == null || otc.id() == null || otc.id().isBlank()
                        || otc.function().name() == null || otc.function().name().isBlank())
                    throw new LLMException("API response has invalid tool identity");
                JsonNode argsNode = parseToolArguments(otc.function().name(), otc.function().arguments());
                toolCalls.add(new ToolCall(otc.id(), otc.function().name(), argsNode));
            }
            return Message.assistantWithTools(msg.content(), toolCalls, msg.reasoningContent());
        }

        // 纯文本回复
        return new Message(Role.ASSISTANT, msg.content() != null ? msg.content() : "",
            null, null, msg.reasoningContent());
    }

    JsonNode parseToolArguments(String name, String arguments) {
        if (arguments == null) throw new LLMException("Missing tool arguments");
        try (var parser = objectMapper.createParser(arguments)) {
            JsonNode value = objectMapper.readTree(parser);
            if (value == null || !value.isObject() || parser.nextToken() != null)
                throw new LLMException("Tool arguments must be exactly one JSON object");
            return value;
        } catch (IOException e) {
            throw new LLMException("Invalid tool argument JSON", e);
        }
    }
}
