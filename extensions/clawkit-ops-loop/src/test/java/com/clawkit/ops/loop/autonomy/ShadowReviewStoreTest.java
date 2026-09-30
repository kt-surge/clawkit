package com.clawkit.ops.loop.autonomy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShadowReviewStoreTest {
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");

    @TempDir Path tempDir;

    @Test
    void reviewIsImmutableIdempotentAndHasNoSideEffect() throws Exception {
        var store = new ShadowReviewStore(tempDir);
        var review = ShadowReview.from(decision(), ShadowReviewDecision.NEEDS_MORE_EVIDENCE, NOW);

        var first = store.record(review);
        var replay = store.record(review);

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(first.review().sideEffectCalls()).isZero();
        try (var entries = Files.list(tempDir)) {
            assertThat(entries.filter(path -> path.getFileName().toString().endsWith(".json")).count())
                .isEqualTo(1);
        }
    }

    @Test
    void differentHumanChoiceCannotOverwriteExistingReview() throws Exception {
        var store = new ShadowReviewStore(tempDir);
        store.record(ShadowReview.from(decision(), ShadowReviewDecision.WOULD_REJECT, NOW));

        assertThatThrownBy(() -> store.record(ShadowReview.from(decision(), ShadowReviewDecision.WOULD_APPROVE,
            NOW.plusSeconds(1))))
            .hasMessageContaining("different immutable decision");
    }

    @Test
    void separateStoresConcurrentlyRecordTheSameReviewExactlyOnce() throws Exception {
        var review = ShadowReview.from(decision(), ShadowReviewDecision.WOULD_APPROVE, NOW);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> recordAfterBarrier(review, ready, start));
            var second = pool.submit(() -> recordAfterBarrier(review, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(5, TimeUnit.SECONDS).created(),
                second.get(5, TimeUnit.SECONDS).created())).containsExactlyInAnyOrder(true, false);
        }
        try (var entries = Files.list(tempDir)) {
            assertThat(entries.filter(path -> path.getFileName().toString().endsWith(".json")).count())
                .isEqualTo(1);
        }
    }

    @Test
    void concurrentDifferentChoicesNeverOverwriteTheFirstImmutableReview() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var approve = pool.submit(() -> recordChoiceAfterBarrier(ShadowReviewDecision.WOULD_APPROVE, ready, start));
            var reject = pool.submit(() -> recordChoiceAfterBarrier(ShadowReviewDecision.WOULD_REJECT, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(approve.get(5, TimeUnit.SECONDS), reject.get(5, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("created", "conflict");
        }
        try (var entries = Files.list(tempDir)) {
            assertThat(entries.filter(path -> path.getFileName().toString().endsWith(".json")).count())
                .isEqualTo(1);
        }
    }

    private ShadowReviewStore.RecordedReview recordAfterBarrier(ShadowReview review, CountDownLatch ready,
        CountDownLatch start) throws Exception {
        var store = new ShadowReviewStore(tempDir);
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test start barrier timed out");
        return store.record(review);
    }

    private String recordChoiceAfterBarrier(ShadowReviewDecision choice, CountDownLatch ready,
        CountDownLatch start) throws Exception {
        var review = ShadowReview.from(decision(), choice, NOW);
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test start barrier timed out");
        try {
            return new ShadowReviewStore(tempDir).record(review).created() ? "created" : "replayed";
        } catch (java.io.IOException e) {
            if (e.getMessage().contains("different immutable decision")) return "conflict";
            throw e;
        }
    }

    private static ShadowDecision decision() {
        String policy = "a".repeat(64), evidence = "b".repeat(64);
        String id = ShadowDecision.deterministicId("inc-1", "fixture-app-down", evidence, policy,
            "restart_service", "order-api");
        return new ShadowDecision(id, NOW, "inc-1", "fixture-app-down", evidence, policy,
            "restart_service", "order-api", ShadowOutcome.ASK_REQUIRED, List.of("EVIDENCE_STALE"),
            ModelOpinion.UNSPECIFIED, 0);
    }
}
