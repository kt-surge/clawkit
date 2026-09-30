package com.clawkit.ops.loop.automation;

import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDK 21 {@link ScheduledThreadPoolExecutor} adapter implementing
 * {@link AutomationTaskScheduler}.
 *
 * <p>Uses fixed-delay scheduling. Wraps every task so that a
 * {@link Throwable} does not halt subsequent periodic executions
 * (except {@link VirtualMachineError} and {@link ThreadDeath}).
 */
public final class JdkAutomationTaskScheduler implements AutomationTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(JdkAutomationTaskScheduler.class);
    private final ScheduledThreadPoolExecutor executor;

    public JdkAutomationTaskScheduler(int corePoolSize) {
        if (corePoolSize < 1) {
            throw new IllegalArgumentException("corePoolSize must be positive");
        }
        this.executor = new ScheduledThreadPoolExecutor(corePoolSize, threadFactory());
        executor.setRemoveOnCancelPolicy(true);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override
    public ScheduledHandle schedule(
        Runnable task, long initialDelay, long delay, TimeUnit unit
    ) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unit, "unit");
        if (initialDelay < 0) throw new IllegalArgumentException("initialDelay must not be negative");
        if (delay <= 0) throw new IllegalArgumentException("delay must be positive");
        Runnable wrapped = () -> {
            try {
                task.run();
            } catch (VirtualMachineError | ThreadDeath e) {
                throw e; // re-throw fatal errors
            } catch (Throwable t) {
                log.error("Automation task threw exception — subsequent cycles continue", t);
            }
        };
        var future = executor.scheduleWithFixedDelay(
            wrapped, initialDelay, delay, unit);
        return new ScheduledHandle.Record(future);
    }

    @Override
    public void close() {
        // Periodic/delayed work is cancelled by the configured shutdown
        // policies; an observation already running is allowed to finish.
        executor.shutdown();
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger counter = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, "clawkit-automation-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
