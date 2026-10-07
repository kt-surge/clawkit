package com.clawkit.context.impl;

import com.clawkit.tools.schema.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExchangeLayoutTest {
    @Test void parallelToolsStayTogetherAndAPlainAcknowledgmentDoesNotInventAnotherExchange() {
        var messages = List.of(Message.user("preview only"),
            Message.assistantWithTools(List.of(new ToolCall("a", "read", null), new ToolCall("b", "read", null))),
            Message.toolResult("b", "second result"), Message.toolResult("a", "first result"), Message.assistant("both checked"),
            Message.assistantWithTools(List.of(new ToolCall("c", "read", null))), Message.toolResult("c", "next result"));
        var layout = ExchangeLayout.inspect(messages);
        assertThat(layout.valid()).isTrue(); assertThat(layout.count()).isEqualTo(2);
        assertThat(layout.groups()).containsExactly(new ExchangeLayout.Group(1, 0, 5), new ExchangeLayout.Group(2, 5, 7));
        assertThat(layout.protectedUser(0)).isTrue();
    }
    @Test void incompleteOrDuplicateToolGraphsAreNeverPartiallyEvicted() {
        for (var messages : List.of(
            List.of(Message.user("task"), Message.assistantWithTools(List.of(new ToolCall("a", "read", null))), Message.assistant("still pending")),
            List.of(Message.user("task"), Message.assistantWithTools(List.of(new ToolCall("a", "read", null))), Message.toolResult("a", "done"),
                Message.assistantWithTools(List.of(new ToolCall("a", "read", null))), Message.toolResult("a", "duplicate")))) {
            var layout = ExchangeLayout.inspect(messages);
            assertThat(layout.valid()).isFalse(); assertThat(layout.recentBoundary(1)).isZero();
            var masked = MessageMasker.mask(messages, 30);
            assertThat(masked.messages()).isEqualTo(messages); assertThat(masked.evictedTurnGroups()).isEmpty();
        }
    }
    @Test void onlyTheLatestThreeUserRequestsReceiveSeparateVerbatimProtection() {
        var messages = new ArrayList<Message>();
        for (int i = 0; i < 5; i++) { messages.add(Message.user("request " + i)); messages.add(Message.assistant("ack")); }
        var layout = ExchangeLayout.inspect(messages);
        assertThat(layout.count()).isEqualTo(5); assertThat(layout.recentBoundary(3)).isEqualTo(4);
        assertThat(layout.protectedUser(0)).isFalse(); assertThat(layout.protectedUser(2)).isFalse();
        assertThat(layout.protectedUser(4)).isTrue(); assertThat(layout.protectedUser(6)).isTrue(); assertThat(layout.protectedUser(8)).isTrue();
    }
}
