package com.clawkit.context.impl;

import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.Role;
import java.util.*;

/** Private context layout: completed assistant/tool exchanges can age inside a single user request. */
final class ExchangeLayout {
    record Group(int number, int start, int end) {}
    private final List<Group> groups;
    private final int[] groupByIndex;
    private final Set<Integer> protectedUsers;
    private final boolean valid;
    private final Set<Integer> taskSourceGroups;
    private ExchangeLayout(List<Group> groups, int[] groupByIndex, Set<Integer> users, boolean valid, List<Message> messages) {
        this.groups = List.copyOf(groups); this.groupByIndex = groupByIndex;
        this.protectedUsers = Set.copyOf(users); this.valid = valid;
        this.taskSourceGroups = TaskSourceProtection.select(messages, groups, groupByIndex, valid);
    }
    static ExchangeLayout inspect(List<Message> messages) {
        var groups = new ArrayList<Group>(); var pending = new HashSet<String>(); var seen = new HashSet<String>();
        int[] indices = new int[messages.size()]; int start = -1, number = 0; boolean completedExchange = false, valid = true;
        for (int i = 0; i < messages.size(); i++) {
            var message = messages.get(i);
            if (message.role() == Role.SYSTEM) { if (!pending.isEmpty()) valid = false; continue; }
            if (!pending.isEmpty() && message.role() != Role.TOOL) valid = false;
            boolean toolAssistant = message.role() == Role.ASSISTANT && message.toolCalls() != null && !message.toolCalls().isEmpty();
            if (start < 0 || message.role() == Role.USER || (toolAssistant && completedExchange)) {
                if (start >= 0) groups.add(new Group(number, start, i));
                start = i; number++; completedExchange = false;
            }
            indices[i] = number;
            if (message.toolCalls() != null) {
                if (!message.toolCalls().isEmpty() && message.role() != Role.ASSISTANT) valid = false;
                for (var call : message.toolCalls()) {
                    if (call.id() == null || call.id().isBlank() || !seen.add(call.id())) valid = false;
                    pending.add(call.id());
                }
            }
            if (message.role() == Role.TOOL) {
                if (message.toolCallId() == null || !pending.remove(message.toolCallId())) valid = false;
                if (pending.isEmpty()) completedExchange = true;
            }
        }
        if (start >= 0) groups.add(new Group(number, start, messages.size()));
        valid &= pending.isEmpty();
        var users = new HashSet<Integer>();
        for (int i = messages.size() - 1; i >= 0 && users.size() < 3; i--) if (messages.get(i).role() == Role.USER) users.add(i);
        return new ExchangeLayout(groups, indices, users, valid, messages);
    }
    boolean valid() { return valid; }
    int count() { return groups.size(); }
    int groupOf(int index) { return groupByIndex[index]; }
    boolean protectedUser(int index) { return protectedUsers.contains(index); }
    boolean protectedTaskSource(int index) { return taskSourceGroups.contains(groupByIndex[index]); }
    List<Group> groups() { return groups; }
    int recentBoundary(int count) { return valid && groups.size() > count ? groups.get(groups.size() - count).start() : 0; }
}
