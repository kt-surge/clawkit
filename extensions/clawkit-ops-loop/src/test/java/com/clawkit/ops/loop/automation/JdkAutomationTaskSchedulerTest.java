package com.clawkit.ops.loop.automation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class JdkAutomationTaskSchedulerTest {

    private JdkAutomationTaskScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) scheduler.close();
    }

    // ── Test 14: normal exception doesn't halt periodic execution ──

    @Test
    void taskExceptionDoesNotHaltSubsequentCycles() throws Exception {
        scheduler = new JdkAutomationTaskScheduler(2);
        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(5);
        AtomicInteger exceptions = new AtomicInteger(0);

        scheduler.schedule(() -> {
            int c = count.incrementAndGet();
            latch.countDown();
            if (c <= 2) {
                exceptions.incrementAndGet();
                throw new RuntimeException("simulated failure " + c);
            }
        }, 0, 10, TimeUnit.MILLISECONDS);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(count.get()).isGreaterThanOrEqualTo(5);
        assertThat(exceptions.get()).isEqualTo(2);
    }

    // ── Test 15: cancel removes task from queue ──

    @Test
    void cancelStopsFutureExecutions() throws Exception {
        scheduler = new JdkAutomationTaskScheduler(2);
        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch firstLatch = new CountDownLatch(1);

        var handle = scheduler.schedule(() -> {
            count.incrementAndGet();
            firstLatch.countDown();
        }, 0, 10, TimeUnit.MILLISECONDS);

        // Wait for first execution
        assertThat(firstLatch.await(5, TimeUnit.SECONDS)).isTrue();
        int afterFirst = count.get();
        assertThat(afterFirst).isGreaterThanOrEqualTo(1);

        // Cancel
        assertThat(handle.cancel(false)).isTrue();
        assertThat(handle.isCancelled()).isTrue();

        // Wait to verify no more executions
        Thread.sleep(200);
        assertThat(count.get()).isEqualTo(afterFirst);
    }

    @Test
    void tasksExecuteWithFixedDelay() throws Exception {
        scheduler = new JdkAutomationTaskScheduler(2);
        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3);

        scheduler.schedule(() -> {
            count.incrementAndGet();
            latch.countDown();
        }, 0, 20, TimeUnit.MILLISECONDS);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(count.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void closeDoesNotInterruptTaskAlreadyRunning() throws Exception {
        scheduler = new JdkAutomationTaskScheduler(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        scheduler.schedule(() -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        }, 0, 1, TimeUnit.DAYS);

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        scheduler.close();
        release.countDown();

        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isFalse();
    }
}
