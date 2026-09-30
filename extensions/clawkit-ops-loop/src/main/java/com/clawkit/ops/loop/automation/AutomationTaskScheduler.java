package com.clawkit.ops.loop.automation;

import java.util.concurrent.TimeUnit;

/**
 * Minimal scheduler abstraction for recurring automation tasks.
 *
 * <p>Supports fixed-delay scheduling only. Implementations must ensure
 * that a task exception does not silently halt subsequent executions.
 */
public interface AutomationTaskScheduler extends AutoCloseable {
    /**
     * Schedule a task with fixed delay between completion and next start.
     *
     * @param task         the runnable to execute
     * @param initialDelay delay before first execution
     * @param delay        delay between the end of one execution and start of next
     * @param unit         time unit
     * @return a handle that can cancel the task
     */
    ScheduledHandle schedule(
        Runnable task, long initialDelay, long delay, TimeUnit unit);

    /** Initiate an orderly shutdown. */
    @Override
    void close();
}
