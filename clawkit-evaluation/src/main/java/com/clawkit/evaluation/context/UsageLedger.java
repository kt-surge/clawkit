package com.clawkit.evaluation.context;

import com.clawkit.provider.TokenUsage;
import com.clawkit.provider.UsageSource;
import java.util.ArrayList;
import java.util.List;

/** Never turns absent/synthetic usage into actual zero-cost model work. */
public final class UsageLedger {
    public record Entry(String callId, String phase, String status, long durationMs,
                        TokenUsage usage, String failureType) {}
    public record Totals(int calls, int failedCalls, int actualCalls, int unavailableCalls,
                         int estimatedCalls, Long actualInputTokens, Long actualOutputTokens,
                         Long actualTotalTokens, Long cacheHitInputTokens, Long cacheMissInputTokens,
                         Long reasoningOutputTokens, boolean completeActualUsage) {}

    private final List<Entry> entries = new ArrayList<>();

    public synchronized void add(Entry entry) {
        if (entry.callId() == null || entry.phase() == null || entry.durationMs() < 0
                || entry.usage() == null || entries.stream().anyMatch(e -> e.callId().equals(entry.callId()))) {
            throw new IllegalArgumentException("valid unique usage entry required");
        }
        entries.add(entry);
    }

    public synchronized List<Entry> entries() { return List.copyOf(entries); }
    public synchronized Totals totals() { return total(entries); }

    public static Totals total(List<Entry> entries) {
        var actual = entries.stream().filter(e -> e.usage().source() == UsageSource.ACTUAL).toList();
        int absent = (int) entries.stream().filter(e -> e.usage().source() == UsageSource.UNAVAILABLE).count();
        int estimated = entries.size() - actual.size() - absent;
        boolean present = !actual.isEmpty();
        return new Totals(entries.size(), (int) entries.stream().filter(e -> e.failureType() != null).count(),
            actual.size(), absent, estimated,
            present ? sum(actual, TokenUsage::promptTokens) : null,
            present ? sum(actual, TokenUsage::completionTokens) : null,
            present ? sum(actual, TokenUsage::totalTokens) : null,
            present ? sum(actual, TokenUsage::promptCacheHitTokens) : null,
            present ? sum(actual, TokenUsage::promptCacheMissTokens) : null,
            present ? sum(actual, TokenUsage::reasoningTokens) : null,
            !entries.isEmpty() && absent == 0 && estimated == 0);
    }

    private static long sum(List<Entry> entries, java.util.function.ToIntFunction<TokenUsage> field) {
        return entries.stream().map(Entry::usage).mapToLong(field::applyAsInt).sum();
    }
}
