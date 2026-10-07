package com.clawkit.context.impl;

import com.clawkit.context.Constraint;
import com.clawkit.context.ConstraintExtractor;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.Role;
import com.clawkit.tools.schema.ToolCall;
import java.nio.file.Path;
import java.util.*;

/** Bounded verbatim retention of user-referenced read exchanges, with no role or trust promotion. */
final class TaskSourceProtection {
    static final int MAX_SOURCES = 2;
    static final int MAX_SOURCE_CHARS = 4096;
    static final int MAX_EXCHANGE_CHARS = 8192;

    private TaskSourceProtection() {}

    static Set<Integer> select(List<Message> messages, List<ExchangeLayout.Group> groups,
                               int[] groupByIndex, boolean valid) {
        if (!valid) return Set.of();
        int userIndex = -1;
        for (int i = messages.size()-1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) { userIndex = i; break; }
        }
        if (userIndex < 0) return Set.of();
        var referenced = new HashSet<String>();
        for (Constraint constraint : new ConstraintExtractor().extract(List.of(messages.get(userIndex)))) {
            if (constraint instanceof Constraint.FilePath) {
                String path = normalizedPath(constraint.text());
                if (path != null) referenced.add(path);
            }
        }
        if (referenced.isEmpty()) return Set.of();
        var calls = new HashMap<String, ToolCall>();
        var latestReadIndex = new HashMap<String, Integer>();
        for (int i = userIndex+1; i < messages.size(); i++) {
            var message = messages.get(i);
            if (message.role() == Role.ASSISTANT && message.toolCalls() != null) {
                for (ToolCall call : message.toolCalls()) calls.put(call.id(), call);
            }
            if (message.role() != Role.TOOL) continue;
            ToolCall call = calls.get(message.toolCallId());
            if (call == null || !"read".equals(call.name()) || call.arguments() == null) continue;
            var pathNode = call.arguments().get("path");
            if (pathNode == null || !pathNode.isTextual()) continue;
            String path = normalizedPath(pathNode.asText());
            if (path != null && referenced.contains(path)) latestReadIndex.put(path, i);
        }
        var indices = new ArrayList<>(latestReadIndex.values());
        indices.sort(Comparator.reverseOrder());
        var retained = new HashSet<Integer>();
        int sourceCount = 0, retainedChars = 0;
        for (int index : indices) {
            if (sourceCount >= MAX_SOURCES) break;
            String text = messages.get(index).content();
            if (text == null || text.isBlank() || text.length() > MAX_SOURCE_CHARS
                || text.startsWith("[tool output")) continue;
            int number = groupByIndex[index];
            var group = groups.get(number-1);
            int chars = retained.contains(number) ? 0 : exchangeChars(messages, group);
            if (chars > MAX_EXCHANGE_CHARS - retainedChars) continue;
            retained.add(number); retainedChars += chars; sourceCount++;
        }
        return Set.copyOf(retained);
    }

    private static int exchangeChars(List<Message> messages, ExchangeLayout.Group group) {
        long chars = 0;
        for (int i = group.start(); i < group.end(); i++) {
            var message = messages.get(i);
            if (message.role() == Role.SYSTEM) continue;
            chars += 128L + (message.content() == null ? 0 : message.content().length());
            if (message.reasoningContent() != null) chars += message.reasoningContent().length();
            if (message.toolCallId() != null) chars += message.toolCallId().length();
            if (message.toolCalls() != null) for (ToolCall call : message.toolCalls()) {
                chars += call.id().length() + (call.name() == null ? 0 : call.name().length());
                if (call.arguments() != null) chars += call.arguments().toString().length();
            }
            if (chars > MAX_EXCHANGE_CHARS) return MAX_EXCHANGE_CHARS+1;
        }
        return (int) chars;
    }

    private static String normalizedPath(String path) {
        if (path == null || path.isBlank() || path.length() > 512
            || path.codePoints().anyMatch(Character::isISOControl)) return null;
        try { return Path.of(path.replace('\\', '/')).normalize().toString().replace('\\', '/'); }
        catch (RuntimeException invalid) { return null; }
    }
}
