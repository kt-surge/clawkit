package com.clawkit.cli.ops;

import com.clawkit.ops.delivery.ApprovalDecision;
import com.clawkit.ops.delivery.UserIncidentStatus;
import com.clawkit.ops.loop.autonomy.ModelOpinion;
import com.clawkit.ops.loop.autonomy.ShadowDecision;
import com.clawkit.ops.loop.autonomy.ShadowOutcome;
import com.clawkit.ops.loop.autonomy.ShadowReview;
import com.clawkit.ops.loop.autonomy.ShadowReviewDecision;
import com.clawkit.ops.loop.autonomy.ShadowReviewStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DogfoodLoggerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path home;

    @Test
    void approveRecordsDecisionReadingTimeAndActions() throws Exception {
        MutableClock clock = new MutableClock("2026-08-04T00:00:00Z");
        DogfoodLogger logger = new DogfoodLogger(home, clock);

        logger.beginTask("fixture", "INVESTIGATE", "FIXTURE");
        logger.markApprovalShown();
        clock.advanceSeconds(23);
        logger.recordDecision(ApprovalDecision.APPROVE);
        logger.log("fixture", "INVESTIGATE", null, UserIncidentStatus.RESOLVED, "inc-1");

        JsonNode entry = onlyEntry();
        assertThat(entry.path("decision").asText()).isEqualTo("APPROVE");
        assertThat(entry.path("readingTimeSeconds").asLong()).isEqualTo(23);
        assertThat(entry.path("userActions").asInt()).isEqualTo(2);
        assertThat(entry.path("environment").asText()).isEqualTo("FIXTURE");
    }

    @Test
    void rejectRecordsDecisionAndReadingTime() throws Exception {
        MutableClock clock = new MutableClock("2026-08-04T00:00:00Z");
        DogfoodLogger logger = new DogfoodLogger(home, clock);

        logger.beginTask("test-server", "INVESTIGATE", "REMOTE_READONLY");
        logger.markApprovalShown();
        clock.advanceSeconds(9);
        logger.recordDecision(ApprovalDecision.REJECT);
        logger.log("test-server", "INVESTIGATE", null, UserIncidentStatus.REJECTED, "inc-2");

        JsonNode entry = onlyEntry();
        assertThat(entry.path("decision").asText()).isEqualTo("REJECT");
        assertThat(entry.path("readingTimeSeconds").asLong()).isEqualTo(9);
        assertThat(entry.path("userActions").asInt()).isEqualTo(2);
    }

    @Test
    void investigationWithoutApprovalKeepsDecisionMetricsUnknown() throws Exception {
        DogfoodLogger logger = new DogfoodLogger(home, Clock.fixed(
            Instant.parse("2026-08-04T00:00:00Z"), ZoneId.of("UTC")));

        logger.beginTask("test-server", "INVESTIGATE", "REMOTE_READONLY");
        logger.log("test-server", "INVESTIGATE", null,
            UserIncidentStatus.INCONCLUSIVE, "inc-3");

        JsonNode entry = onlyEntry();
        assertThat(entry.path("decision").asText()).isEqualTo("NONE");
        assertThat(entry.path("readingTimeSeconds").isNull()).isTrue();
        assertThat(entry.path("userActions").asInt()).isEqualTo(1);
        assertThat(entry.path("understood").isNull()).isTrue();
        assertThat(entry.path("fellBackToSsh").isNull()).isTrue();
    }

    @Test
    void feedbackIsAppendedAndSensitiveTextIsRedacted() throws Exception {
        DogfoodLogger logger = new DogfoodLogger(home, Clock.fixed(
            Instant.parse("2026-08-04T00:00:00Z"), ZoneId.of("UTC")));

        logger.logFeedback("inc-4", true, true, "看到 sk-secret 后仍不确定", "HIGH");

        JsonNode entry = onlyEntry();
        assertThat(entry.path("type").asText()).isEqualTo("FRICTION");
        assertThat(entry.path("understood").asBoolean()).isTrue();
        assertThat(entry.path("fellBackToSsh").asBoolean()).isTrue();
        assertThat(entry.path("frictionDescription").asText()).contains("[已脱敏]").doesNotContain("sk-secret");
    }

    @Test
    void shadowReviewIsLoggedAsFixtureOnlyCounterfactualEvidence() throws Exception {
        DogfoodLogger logger = new DogfoodLogger(home, Clock.fixed(
            Instant.parse("2026-09-20T12:00:00Z"), ZoneId.of("UTC")));
        String policy = "a".repeat(64), evidence = "b".repeat(64);
        String id = ShadowDecision.deterministicId("inc-1", "fixture-app-down", evidence, policy,
            "restart_service", "order-api");
        var decision = new ShadowDecision(id, Instant.parse("2026-09-20T11:59:00Z"), "inc-1",
            "fixture-app-down", evidence, policy, "restart_service", "order-api", ShadowOutcome.ASK_REQUIRED,
            List.of("EVIDENCE_STALE"), ModelOpinion.UNSPECIFIED, 0);
        logger.logShadowReview(ShadowReview.from(decision, ShadowReviewDecision.NEEDS_MORE_EVIDENCE,
            Instant.parse("2026-09-20T12:00:00Z")));

        JsonNode entry = onlyEntry();
        assertThat(entry.path("type").asText()).isEqualTo("SHADOW_REVIEW");
        assertThat(entry.path("environment").asText()).isEqualTo("FIXTURE");
        assertThat(entry.path("reviewId").asText()).matches("shadow-review-[0-9a-f]{32}");
        assertThat(entry.path("reviewerDecision").asText()).isEqualTo("NEEDS_MORE_EVIDENCE");
        assertThat(entry.path("sideEffectCalls").asInt()).isZero();
        assertThat(entry.path("policyHash").asText()).isEqualTo(policy);
        assertThat(entry.has("target")).isFalse();
    }

    @Test
    void replayedImmutableReviewDoesNotInflateTheDogfoodSample() throws Exception {
        DogfoodLogger logger = new DogfoodLogger(home, Clock.fixed(
            Instant.parse("2026-09-20T12:00:00Z"), ZoneId.of("UTC")));
        JLineInvestigationInteraction interaction = new JLineInvestigationInteraction(null);
        interaction.setDogfoodLogger(logger);
        ShadowReview review = shadowReview(ShadowReviewDecision.WOULD_REJECT);

        interaction.logDogfoodShadowReview(new ShadowReviewStore.RecordedReview(review, true));
        interaction.logDogfoodShadowReview(new ShadowReviewStore.RecordedReview(review, false));

        assertThat(logger.summary().shadowReviews()).isEqualTo(1);
        assertThat(logger.summary().wouldReject()).isEqualTo(1);
    }

    @Test
    void summaryCountsOnlyValidAggregateSignalsAndRequiresConsecutiveDays() throws Exception {
        MutableClock clock = new MutableClock("2026-09-20T00:00:00Z");
        DogfoodLogger logger = new DogfoodLogger(home, clock);
        logger.beginTask("fixture", "INVESTIGATE", "FIXTURE");
        logger.log("fixture", "INVESTIGATE", null, UserIncidentStatus.INCONCLUSIVE, "inc-1");
        clock.advanceSeconds(24 * 60 * 60);
        logger.logFeedback("inc-1", true, false, "清楚", "LOW");
        clock.advanceSeconds(24 * 60 * 60);
        logger.logShadowReview(shadowReview(ShadowReviewDecision.WOULD_APPROVE));

        var summary = logger.summary();
        assertThat(summary.logPresent()).isTrue();
        assertThat(summary.validEvents()).isEqualTo(3);
        assertThat(summary.invalidEvents()).isZero();
        assertThat(summary.recordedDays()).isEqualTo(3);
        assertThat(summary.consecutiveDays()).isTrue();
        assertThat(summary.taskEvents()).isEqualTo(1);
        assertThat(summary.frictionEvents()).isEqualTo(1);
        assertThat(summary.shadowReviews()).isEqualTo(1);
        assertThat(summary.wouldApprove()).isEqualTo(1);
        assertThat(summary.hasSevenConsecutiveDays()).isFalse();
    }

    @Test
    void summaryCountsMalformedLinesWithoutTreatingThemAsEvidence() throws Exception {
        DogfoodLogger logger = new DogfoodLogger(home, Clock.fixed(
            Instant.parse("2026-09-20T00:00:00Z"), ZoneId.of("UTC")));
        logger.logShadowReview(shadowReview(ShadowReviewDecision.WOULD_REJECT));
        Files.writeString(home.resolve("dogfood").resolve("usage.jsonl"), "not-json\n",
            java.nio.file.StandardOpenOption.APPEND);

        var summary = logger.summary();
        assertThat(summary.validEvents()).isEqualTo(1);
        assertThat(summary.invalidEvents()).isEqualTo(1);
        assertThat(summary.shadowReviews()).isEqualTo(1);
    }

    @Test
    void summaryDoesNotTreatDateOnlyJsonAsARealTask() throws Exception {
        DogfoodLogger logger = new DogfoodLogger(home, Clock.fixed(
            Instant.parse("2026-09-20T00:00:00Z"), ZoneId.of("UTC")));
        logger.beginTask("test-server", "INVESTIGATE", "REMOTE_READONLY");
        logger.log("test-server", "INVESTIGATE", null,
            UserIncidentStatus.INCONCLUSIVE, "inc-real-task");
        Files.writeString(home.resolve("dogfood").resolve("usage.jsonl"),
            "{\"date\":\"2026-09-20\"}\n", java.nio.file.StandardOpenOption.APPEND);

        var summary = logger.summary();
        assertThat(summary.validEvents()).isEqualTo(1);
        assertThat(summary.invalidEvents()).isEqualTo(1);
        assertThat(summary.taskEvents()).isEqualTo(1);
    }

    @Test
    void summaryRejectsDuplicateImmutableReviewIdsInsteadOfInflatingTheSample() throws Exception {
        DogfoodLogger logger = new DogfoodLogger(home, Clock.fixed(
            Instant.parse("2026-09-20T00:00:00Z"), ZoneId.of("UTC")));
        ShadowReview review = shadowReview(ShadowReviewDecision.WOULD_REJECT);
        logger.logShadowReview(review);
        logger.logShadowReview(review);

        var summary = logger.summary();
        assertThat(summary.validEvents()).isEqualTo(1);
        assertThat(summary.invalidEvents()).isEqualTo(1);
        assertThat(summary.shadowReviews()).isEqualTo(1);
        assertThat(summary.wouldReject()).isEqualTo(1);
    }

    private static ShadowReview shadowReview(ShadowReviewDecision choice) {
        String policy = "a".repeat(64), evidence = "b".repeat(64);
        String id = ShadowDecision.deterministicId("inc-1", "fixture-app-down", evidence, policy,
            "restart_service", "order-api");
        var decision = new ShadowDecision(id, Instant.parse("2026-09-20T11:59:00Z"), "inc-1",
            "fixture-app-down", evidence, policy, "restart_service", "order-api", ShadowOutcome.ASK_REQUIRED,
            List.of("EVIDENCE_STALE"), ModelOpinion.UNSPECIFIED, 0);
        return ShadowReview.from(decision, choice, Instant.parse("2026-09-20T12:00:00Z"));
    }

    private JsonNode onlyEntry() throws Exception {
        Path log = home.resolve("dogfood").resolve("usage.jsonl");
        return MAPPER.readTree(Files.readAllLines(log).getFirst());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(String initial) { this.now = Instant.parse(initial); }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
