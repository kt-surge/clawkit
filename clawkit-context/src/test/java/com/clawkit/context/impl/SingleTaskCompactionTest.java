package com.clawkit.context.impl;

import com.clawkit.context.*;
import com.clawkit.tools.schema.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** A single real-shaped ReAct task has many completed tool exchanges but only one USER message. */
class SingleTaskCompactionTest {
    static final String TASK = "检查配置并输出结论。整个任务只允许生成预览，禁止删除文件、重启服务或应用到生产。";
    @Test void singleRequestCanSummarizeOldExchangesWhileKeepingTheTaskAndRecentToolPairs() throws Exception {
        var tokenizer = TokenizerFactory.create("cl100k_base"); var policy = ContextBudgetPolicy.of(4096);
        var summaries = new AtomicInteger();
        var pipeline = new DefaultContextPipeline(new LadderedCompactor(messages -> {
            summaries.incrementAndGet(); return "此前已经逐项检查旧配置，继续处理剩余项目；预览以外操作仍被禁止。";
        }, tokenizer), new ContextBudgetAnalyzer(tokenizer, policy), tokenizer, policy);
        var input = history(24); int reserve = 1024, safety = 32;
        var result = pipeline.compact(new CompactionRequest(input, 128, 25, CompactionHint.GENERAL, reserve, safety, 40_000));
        assertThat(result.audit().failureCode()).isNull();
        assertThat(summaries.get()).isPositive();
        assertThat(result.afterReport().totalTokens()).isLessThanOrEqualTo(policy.hardLimitTokens() - reserve - safety);
        assertThat(result.messages().stream().filter(message -> message.role() == Role.USER && TASK.equals(message.content())).count()).isEqualTo(1);
        assertValidPairs(result.messages());
        for (int turn = 22; turn <= 24; turn++) for (String suffix : List.of("a", "b")) {
            String id = "inspect-" + turn + suffix;
            assertThat(result.messages()).anyMatch(message -> id.equals(message.toolCallId()));
            assertThat(result.messages()).anyMatch(message -> message.toolCalls() != null && message.toolCalls().stream().anyMatch(call -> id.equals(call.id())));
        }
    }
    @Test void maskingEvictsCompleteOldExchangesAndRetainsTheSingleCurrentTask() throws Exception {
        var masked = MessageMasker.mask(history(24), 25);
        assertThat(masked.evictedTurnGroups()).isNotEmpty();
        assertThat(masked.messages()).anyMatch(message -> message.role() == Role.USER && TASK.equals(message.content()));
        assertValidPairs(masked.messages());
        for (var group : masked.evictedTurnGroups()) assertValidPairs(group.messages());
    }
    static List<Message> history(int count) throws Exception {
        var messages = new ArrayList<Message>(); messages.add(Message.system("只使用本次提供的工作区工具。")); messages.add(Message.user(TASK));
        var mapper = new ObjectMapper();
        for (int turn = 1; turn <= count; turn++) {
            var calls = new ArrayList<ToolCall>();
            for (String suffix : List.of("a", "b")) calls.add(new ToolCall("inspect-" + turn + suffix, "read",
                mapper.createObjectNode().put("path", "samples/config-" + turn + suffix + ".json")));
            messages.add(Message.assistantWithTools("检查第 " + turn + " 组配置。", calls, null));
            for (String suffix : List.of("a", "b")) {
                var sample = new StringBuilder("worker,count,latency,queue,ready\n");
                for (int row = 0; row < 8; row++) sample.append(turn).append(',').append(100 + row).append(',')
                    .append(43 + turn + row).append(',').append(2 + row).append(",false\n");
                messages.add(Message.toolResult("inspect-" + turn + suffix, sample.toString()));
            }
        }
        return messages;
    }
    static void assertValidPairs(List<Message> messages) {
        var pending = new HashSet<String>(); var seen = new HashSet<String>();
        for (var message : messages) {
            if (!pending.isEmpty()) assertThat(message.role()).isEqualTo(Role.TOOL);
            if (message.toolCalls() != null) for (var call : message.toolCalls()) {
                assertThat(seen.add(call.id())).isTrue(); assertThat(pending.add(call.id())).isTrue();
            }
            if (message.role() == Role.TOOL) assertThat(pending.remove(message.toolCallId())).isTrue();
        }
        assertThat(pending).isEmpty();
    }
}
