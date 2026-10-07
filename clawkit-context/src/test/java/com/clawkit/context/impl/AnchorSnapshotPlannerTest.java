package com.clawkit.context.impl;

import com.clawkit.context.*;
import com.clawkit.tools.schema.Message;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnchorSnapshotPlannerTest {
    private final CharFallbackTokenizer tokenizer = new CharFallbackTokenizer();
    private final ContextBudgetPolicy budget = new ContextBudgetPolicy(4096, .70, .80, .95, .65);
    private final AnchorSnapshotPlanner planner = new AnchorSnapshotPlanner(tokenizer, budget,
        new AdaptiveCompactionPolicy(0, 64, .10, 8192));

    @Test void sealedDevelopmentFailureRetainsRelativeFilesWithoutPromotingNonUserText() throws Exception {
        var properties = new java.util.Properties();
        try (var in = getClass().getResourceAsStream("/context/combined-memory-m1-anchor-overflow.properties")) {
            assertThat(in).isNotNull();
            properties.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        var messages = new java.util.ArrayList<Message>();
        for (int i = 0; i < Integer.parseInt(properties.getProperty("message.count")); i++) {
            String content = properties.getProperty("message." + i + ".content");
            messages.add(switch (properties.getProperty("message." + i + ".role")) {
                case "system" -> Message.system(content);
                case "user" -> Message.user(content);
                case "assistant" -> Message.assistant(content);
                case "tool" -> Message.toolResult("recorded-" + i, content);
                default -> throw new IllegalArgumentException("unexpected recorded role");
            });
        }
        assertThat(new ConstraintExtractor().extract(messages).stream().map(Constraint::text))
            .containsExactly(".clawkit/reliability/store.lock", "archive/notify-discussion.json", "archive/repair-discussion.json");
        var actualTokenizer = new TiktokenTokenizer("cl100k_base");
        var actualPlanner = new AnchorSnapshotPlanner(actualTokenizer, budget, AdaptiveCompactionPolicy.defaults(budget));
        var plan = actualPlanner.prepare(messages, CompactionHint.GENERAL);
        assertThat(plan.failed()).isFalse();
        assertThat(plan.snapshot().requiredIds()).isEmpty();
        assertThat(plan.effectiveHint().anchors()).noneMatch(CompactionAnchor::required)
            .noneMatch(anchor -> anchor.provenance() == AnchorProvenance.USER);
    }

    @Test void smallerPlanningBudgetDropsOptionalEvidenceBeforeRequiredUserConstraints() {
        var input=List.of(Message.user("keep /workspace/required.md unchanged"),Message.toolResult("source", "/workspace/one.md /workspace/two.md /workspace/three.md"));
        var full=planner.prepare(input,CompactionHint.GENERAL);
        var required=AnchorSnapshot.render(new CompactionHint(CompactionProfile.GENERAL,
            full.effectiveHint().anchors().stream().filter(CompactionAnchor::required).toList()),512,64);
        int requiredTokens=tokenizer.countTokens(required.renderedText());
        var small=planner.prepare(input,CompactionHint.GENERAL,requiredTokens);
        assertThat(small.failed()).isFalse();
        assertThat(small.snapshot().requiredIds()).isEqualTo(full.snapshot().requiredIds());
        assertThat(small.effectiveHint().anchors()).allMatch(CompactionAnchor::required);
        assertThat(small.tokenCount()).isLessThanOrEqualTo(requiredTokens);
        assertThat(planner.prepare(input,CompactionHint.GENERAL,requiredTokens-1).failureCode()).isEqualTo("REQUIRED_ANCHORS_OVER_BUDGET");
    }

    @Test void ordinaryToolPathsCannotBecomeRequiredConfirmedUserConstraints() {
        var plan = planner.prepare(List.of(Message.toolResult("list-files",
            java.util.stream.IntStream.range(0, 7).mapToObj(i ->
                "D:/Agent/miniclaw/tmp/cm-development-live/agents/combined-memory/archive/source-" + i + ".md")
                .collect(java.util.stream.Collectors.joining("\n")))), CompactionHint.GENERAL);
        assertThat(plan.failed()).isFalse();
        assertThat(plan.snapshot().requiredIds()).isEmpty();
        assertThat(plan.effectiveHint().anchors()).isNotEmpty().allSatisfy(anchor -> {
            assertThat(anchor.kind()).isEqualTo(AnchorKind.EVIDENCE);
            assertThat(anchor.provenance()).isEqualTo(AnchorProvenance.TOOL_EVIDENCE);
            assertThat(anchor.state()).isEqualTo(CompactionAnchor.OPEN);
            assertThat(anchor.required()).isFalse();
        });
    }

    @Test void userSourceWinsOverToolAndModelCopiesInEitherMessageOrder() {
        String path = "/srv/orders/health.json";
        var user = Message.user("preserve " + path);
        var tool = Message.toolResult("read", path);
        var model = Message.assistant("maybe " + path);
        for (var messages : List.of(List.of(tool, model, user), List.of(user, model, tool))) {
            var plan = planner.prepare(messages, CompactionHint.GENERAL);
            assertThat(plan.effectiveHint().anchors()).singleElement().satisfies(anchor -> {
                assertThat(anchor.summary()).isEqualTo(path);
                assertThat(anchor.provenance()).isEqualTo(AnchorProvenance.USER);
                assertThat(anchor.kind()).isEqualTo(AnchorKind.USER_CONSTRAINT);
                assertThat(anchor.required()).isTrue();
            });
        }
    }

    @Test void modelAndSystemTextDoNotImplyUserConfirmation() {
        var plan = planner.prepare(List.of(
            Message.assistant("[ ] restart /srv/orders/prod.json"),
            Message.system("runtime home /srv/clawkit/state")), CompactionHint.GENERAL);
        assertThat(plan.snapshot().requiredIds()).isEmpty();
        assertThat(plan.effectiveHint().anchors()).allSatisfy(anchor ->
            assertThat(anchor.provenance()).isNotEqualTo(AnchorProvenance.USER));
        assertThat(plan.effectiveHint().anchors()).filteredOn(anchor ->
            anchor.provenance() == AnchorProvenance.MODEL_DERIVED).allSatisfy(anchor ->
                assertThat(anchor.kind()).isEqualTo(AnchorKind.OPEN_HYPOTHESIS));
    }

    @Test void explicitRequiredToolEvidenceStillFailsClosedWhenItCannotFit() {
        var narrow = new AnchorSnapshotPlanner(tokenizer, budget,
            new AdaptiveCompactionPolicy(0, 64, .001, 10));
        var evidence = new CompactionAnchor("confirmed-evidence", AnchorKind.CONFIRMED_FACT,
            "confirmed by independently read tool evidence", "run://actual/tool/read", true,
            CompactionAnchor.CONFIRMED, AnchorProvenance.TOOL_EVIDENCE, Instant.EPOCH);
        var plan = narrow.prepare(List.of(),
            new CompactionHint(CompactionProfile.GENERAL, List.of(evidence)));
        assertThat(plan.failureCode()).isEqualTo("REQUIRED_ANCHORS_OVER_BUDGET");
        assertThat(plan.snapshot().requiredIds()).containsExactly("confirmed-evidence");
    }

    @Test void actualUserPathConstraintsAreNotSilentlyDroppedToFitBudget() {
        String paths = java.util.stream.IntStream.range(0, 7).mapToObj(i ->
            "/srv/orders/must-preserve-all-these-independent-targets/config-" + i + ".json")
            .collect(java.util.stream.Collectors.joining("\n"));
        var plan = planner.prepare(List.of(Message.user(paths)), CompactionHint.GENERAL);
        assertThat(plan.failureCode()).isEqualTo("REQUIRED_ANCHORS_OVER_BUDGET");
        assertThat(plan.snapshot().requiredIds()).hasSize(7);
    }
}
