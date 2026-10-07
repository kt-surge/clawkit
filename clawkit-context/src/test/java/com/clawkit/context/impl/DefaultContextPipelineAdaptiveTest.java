package com.clawkit.context.impl;

import com.clawkit.context.AdaptiveCompactionPolicy;
import com.clawkit.context.AnchorKind;
import com.clawkit.context.AnchorProvenance;
import com.clawkit.context.CompactionAnchor;
import com.clawkit.context.CompactionHint;
import com.clawkit.context.CompactionLevel;
import com.clawkit.context.CompactionProfile;
import com.clawkit.context.CompactionRequest;
import com.clawkit.context.ContextBudgetAnalyzer;
import com.clawkit.context.ContextBudgetPolicy;
import com.clawkit.context.Summarizer;
import com.clawkit.tools.schema.Message;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultContextPipelineAdaptiveTest {
    private final CharFallbackTokenizer tokenizer = new CharFallbackTokenizer();

    @Test
    void staysAtL0WhenContextIsBelowWarningThreshold() {
        var pipeline = pipeline(2_000, null,
            new AdaptiveCompactionPolicy(0, 64, 0.50, 1_000));
        List<Message> input = List.of(Message.system("stable"), Message.user("short request"));

        var result = pipeline.compact(new CompactionRequest(input, 0, 1));

        assertThat(result.compacted()).isFalse();
        assertThat(result.audit().level()).isEqualTo(CompactionLevel.L0_NONE);
        assertThat(result.messages()).isEqualTo(input);
    }

    @Test
    void reservesAnchorsForTheDecisionButDoesNotDuplicateThemAtL0() {
        var pipeline = pipeline(4_000, null,
            new AdaptiveCompactionPolicy(0, 64, 0.50, 1_000));
        var anchor = new CompactionAnchor("incident-1", AnchorKind.INCIDENT,
            "short incident", null, true, CompactionAnchor.CONFIRMED,
            AnchorProvenance.WORKFLOW_STATE, Instant.EPOCH);

        var result = pipeline.compact(new CompactionRequest(
            List.of(Message.user("short request")), 0, 1,
            new CompactionHint(CompactionProfile.OPS_DIAGNOSIS, List.of(anchor))));

        assertThat(result.audit().level()).isEqualTo(CompactionLevel.L0_NONE);
        assertThat(result.messages()).noneMatch(message -> message.content() != null
            && message.content().startsWith("[Runtime][Compaction Anchors]"));
    }

    @Test
    void usesL1ToDeduplicateRebuildableRuntimeFragments() {
        var pipeline = pipeline(1_000, null,
            new AdaptiveCompactionPolicy(0, 64, 0.50, 1_000));
        String duplicate = "[Runtime] " + "x".repeat(1_200);
        List<Message> input = List.of(
            Message.system("stable"), Message.system(duplicate), Message.system(duplicate));

        var result = pipeline.compact(new CompactionRequest(input, 0, 1));

        assertThat(result.audit().level()).isEqualTo(CompactionLevel.L1_DETERMINISTIC);
        assertThat(result.messages()).filteredOn(message -> duplicate.equals(message.content()))
            .hasSize(1);
        assertThat(result.afterReport().totalTokens()).isLessThan(result.beforeReport().totalTokens());
    }

    @Test
    void escalatesToL3AndReinsertsCanonicalRequiredAnchors() {
        AtomicInteger summaries = new AtomicInteger();
        List<String> summaryInputs = java.util.Collections.synchronizedList(new ArrayList<>());
        Summarizer summarizer = messages -> {
            summaries.incrementAndGet();
            summaryInputs.add(messages.stream().map(Message::content)
                .filter(java.util.Objects::nonNull).reduce("", (left, right) -> left + "\n" + right));
            return "summary deliberately omits every anchor";
        };
        var pipeline = pipeline(1_600, summarizer,
            new AdaptiveCompactionPolicy(0, 64, 0.50, 1_000));
        List<Message> input = longConversation(26, 180);
        var anchor = new CompactionAnchor("fact-1", AnchorKind.CONFIRMED_FACT,
            "database lock confirmed", "evidence://run/call/slice", true,
            CompactionAnchor.CONFIRMED, AnchorProvenance.TOOL_EVIDENCE,
            Instant.parse("2026-07-22T10:00:00Z"));

        var result = pipeline.compact(new CompactionRequest(input, 0, 26,
            new CompactionHint(CompactionProfile.OPS_DIAGNOSIS, List.of(anchor))));

        assertThat(result.audit().level()).isEqualTo(CompactionLevel.L3_GENERATIVE);
        assertThat(result.audit().failureCode()).isNull();
        assertThat(result.audit().retainedAnchorIds()).containsExactly("fact-1");
        assertThat(result.audit().lostRequiredAnchorIds()).isEmpty();
        assertThat(result.audit().evictedGroups()).isGreaterThan(0);
        assertThat(result.messages()).filteredOn(message -> message.content() != null
            && message.content().startsWith("[Runtime][Compaction Anchors]"))
            .singleElement().satisfies(message -> assertThat(message.content())
                .contains("id=fact-1", "database lock confirmed"));
        assertThat(summaries).hasPositiveValue();
        assertThat(summaryInputs).anyMatch(inputText -> inputText.contains("question 1 "))
            .anyMatch(inputText -> inputText.contains("question 10 "));
    }

    @Test
    void failsClosedWhenRequiredAnchorsExceedTheirBudget() {
        var pipeline = pipeline(1_000, null,
            new AdaptiveCompactionPolicy(0, 64, 0.01, 10));
        var anchor = new CompactionAnchor("required-1", AnchorKind.USER_CONSTRAINT,
            "must preserve this long and important constraint", null, true,
            CompactionAnchor.CONFIRMED, AnchorProvenance.USER, Instant.EPOCH);

        var result = pipeline.compact(new CompactionRequest(
            List.of(Message.user("x".repeat(800))), 0, 1,
            new CompactionHint(CompactionProfile.GENERAL, List.of(anchor)),
            0, 0, 200));

        assertThat(result.compacted()).isTrue();
        assertThat(result.audit().level()).isEqualTo(CompactionLevel.L4_FAILED);
        assertThat(result.audit().failureCode()).isEqualTo("REQUIRED_ANCHORS_OVER_BUDGET");
    }

    @Test
    void failsClosedWhenProtectedSystemContentStillExceedsHardLimit() {
        var pipeline = pipeline(400, messages -> "small summary",
            new AdaptiveCompactionPolicy(0, 64, 0.50, 200));
        List<Message> input = List.of(Message.system("S".repeat(2_000)), Message.user("hello"));

        var result = pipeline.compact(new CompactionRequest(input, 0, 1));

        assertThat(result.audit().level()).isEqualTo(CompactionLevel.L4_FAILED);
        assertThat(result.audit().failureCode()).isEqualTo("COMPACT_HARD_LIMIT");
    }

    @Test
    void deduplicatesAnchorUpdatesAndExtractsLegacyConstraintsBeforeMasking() {
        var pipeline = pipeline(1_600, messages -> "bounded summary",
            new AdaptiveCompactionPolicy(0, 64, 0.80, 1_000));
        var old = new CompactionAnchor("state-1", AnchorKind.OPEN_HYPOTHESIS,
            "old", null, false, CompactionAnchor.OPEN, AnchorProvenance.MODEL_DERIVED,
            Instant.parse("2026-07-22T09:00:00Z"));
        var latest = new CompactionAnchor("state-1", AnchorKind.OPEN_HYPOTHESIS,
            "latest", null, false, CompactionAnchor.OPEN, AnchorProvenance.MODEL_DERIVED,
            Instant.parse("2026-07-22T10:00:00Z"));

        List<Message> conversation = new ArrayList<>(longConversation(26, 80));
        conversation.set(1, Message.user("inspect /tmp/orders.log and keep A-123 "
            + "x".repeat(80)));
        var result = pipeline.compact(new CompactionRequest(
            conversation, 0, 26,
            new CompactionHint(CompactionProfile.GENERAL, List.of(old, latest))));

        String snapshot = result.messages().stream().map(Message::content)
            .filter(content -> content != null && content.startsWith("[Runtime][Compaction Anchors]"))
            .findFirst().orElseThrow();
        assertThat(snapshot).contains("id=state-1", "summary=latest", "/tmp/orders.log", "A-123")
            .doesNotContain("summary=old");
        assertThat(snapshot.split("id=state-1", -1)).hasSize(2);
    }

    @Test
    void lowRunBudgetDropsOnlyWholeOldExchangesAndKeepsTheLatestExactSources() {
        var policy=ContextBudgetPolicy.of(16_384);
        var counter=com.clawkit.context.impl.TokenizerFactory.create("cl100k_base");
        var pipeline=new DefaultContextPipeline(new LadderedCompactor(null,counter),new ContextBudgetAnalyzer(counter,policy),counter,policy);
        var input=new ArrayList<Message>();input.add(Message.system("Preserve original scope and permissions."));
        input.add(Message.user("Use docs/rules.md and keep the exact declared conditions."));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var sourceCall=new com.clawkit.tools.schema.ToolCall("rules","read",json.createObjectNode().put("path","docs/rules.md"));
        input.add(Message.assistantWithTools("read rules",List.of(sourceCall),null));
        input.add(Message.toolResult("rules","Important exact rule: preserve latest source evidence."));
        for(int i=1;i<=8;i++) {
            var call=new com.clawkit.tools.schema.ToolCall("write-"+i,"write",json.createObjectNode().put("path","preview/"+i+".json").put("content","legacy synthetic payload ".repeat(160)));
            input.add(Message.assistantWithTools("write preview "+i,List.of(call),null));
            input.add(Message.toolResult(call.id(),"confirmed local write "+i));
        }
        var result=pipeline.compact(new CompactionRequest(input,200,10,CompactionHint.GENERAL,2_048,81,8_000));
        assertThat(result.audit().failed()).isFalse();
        assertThat(counter.countTokens(result.messages())).isLessThanOrEqualTo((int)(8_000*policy.targetRatio())-200-2_048-81);
        assertThat(counter.countTokens(result.messages())).isLessThan(counter.countTokens(input));
        assertThat(result.messages()).contains(input.get(0),input.get(1),input.get(2),input.get(3));
        for(int i=input.size()-6;i<input.size();i++)assertThat(result.messages()).contains(input.get(i));
        var assistantIds=result.messages().stream().filter(m->m.toolCalls()!=null).flatMap(m->m.toolCalls().stream()).map(com.clawkit.tools.schema.ToolCall::id).toList();
        var resultIds=result.messages().stream().filter(m->m.role()==com.clawkit.tools.schema.Role.TOOL).map(Message::toolCallId).toList();
        assertThat(assistantIds).containsExactlyInAnyOrderElementsOf(resultIds);
        assertThat(result.appliedRules()).contains("l2-budget-exchange-eviction");
        assertThat(result.audit().discardedRanges()).anyMatch(range->range.reason().equals("LOW_RUN_BUDGET_COMPLETE_EXCHANGE"));
    }

    @Test
    void lowBudgetNeverPartiallyEvictsAnIncompleteToolGraph() {
        var counter=com.clawkit.context.impl.TokenizerFactory.create("cl100k_base");var policy=ContextBudgetPolicy.of(16_384);
        var pipeline=new DefaultContextPipeline(new LadderedCompactor(null,counter),new ContextBudgetAnalyzer(counter,policy),counter,policy);
        var input=new ArrayList<Message>();input.add(Message.system("scope"));input.add(Message.user("preserve scope"));
        for(int i=0;i<8;i++) {
            var call=new com.clawkit.tools.schema.ToolCall("complete-"+i,"read",new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("path","source-"+i+".json"));
            input.add(Message.assistantWithTools("read source",List.of(call),null));input.add(Message.toolResult(call.id(),"exact source ".repeat(100)));
        }
        input.add(Message.assistantWithTools("pending read",List.of(new com.clawkit.tools.schema.ToolCall("pending","read",null)),null));
        var result=pipeline.compact(new CompactionRequest(input,100,10,CompactionHint.GENERAL,2_048,81,4_000));
        assertThat(result.messages()).containsAll(input);
        assertThat(result.appliedRules()).doesNotContain("l2-budget-exchange-eviction");
        assertThat(result.audit().discardedRanges()).isEmpty();
    }

    @Test
    void includesReservedOutputSafetyMarginAndRunBudgetInTheDecision() {
        var pipeline = pipeline(4_000, null,
            new AdaptiveCompactionPolicy(0, 64, 0.50, 1_000));
        List<Message> input = List.of(Message.user("x".repeat(400)));

        var normal = pipeline.compact(new CompactionRequest(input, 0, 1));
        var budgetConstrained = pipeline.compact(new CompactionRequest(
            input, 0, 1, CompactionHint.GENERAL, 50, 20, 200));

        assertThat(normal.audit().level()).isEqualTo(CompactionLevel.L0_NONE);
        assertThat(budgetConstrained.audit().level()).isEqualTo(CompactionLevel.L2_EXTRACTIVE);
        assertThat(budgetConstrained.audit().decisionReason()).isEqualTo("above-compact-threshold");
    }

    @Test
    void doesNotReportEvictedUnselectedToolPathsAsRetainedConstraints() {
        var pipeline = pipeline(1_600, messages -> "bounded summary",
            new AdaptiveCompactionPolicy(0, 64, 0.10, 1_000));
        var input = new ArrayList<>(longConversation(26, 100));
        String paths = java.util.stream.IntStream.range(0, 7).mapToObj(i ->
            "/srv/orders/old-tool-output/config-" + i + ".json")
            .collect(java.util.stream.Collectors.joining("\n"));
        input.set(3, Message.toolResult("call-1", paths));
        var result = pipeline.compact(new CompactionRequest(input, 0, 26));
        assertThat(result.audit().failureCode()).isNull();
        assertThat(result.retainedConstraints()).allSatisfy(text ->
            assertThat(result.messages()).anyMatch(message ->
                message.content() != null && message.content().contains(text)));
        assertThat(result.retainedConstraints()).doesNotContain("/srv/orders/old-tool-output/config-6.json");
    }

    private DefaultContextPipeline pipeline(int contextWindow, Summarizer summarizer,
                                            AdaptiveCompactionPolicy adaptivePolicy) {
        var budget = new ContextBudgetPolicy(contextWindow, 0.50, 0.70, 0.95, 0.40);
        var analyzer = new ContextBudgetAnalyzer(tokenizer, budget);
        var compactor = new LadderedCompactor(summarizer, tokenizer);
        return new DefaultContextPipeline(compactor, analyzer, tokenizer, budget, adaptivePolicy);
    }

    private List<Message> longConversation(int turns, int contentSize) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("stable system prompt"));
        for (int turn = 1; turn <= turns; turn++) {
            messages.add(Message.user("question " + turn + " " + "u".repeat(contentSize)));
            messages.add(Message.assistantWithTools("answer " + turn + " " + "a".repeat(contentSize),
                List.of(new com.clawkit.tools.schema.ToolCall("call-" + turn, "read", null)), null));
            messages.add(Message.toolResult("call-" + turn, "tool " + "t".repeat(contentSize)));
        }
        return messages;
    }
}
