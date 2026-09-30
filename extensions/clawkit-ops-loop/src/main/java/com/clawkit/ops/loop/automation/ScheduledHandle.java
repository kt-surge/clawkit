package com.clawkit.ops.loop.automation;

/**
 * Handle to a scheduled recurring task. Returned by
 * {@link AutomationTaskScheduler#schedule}. The handle can cancel the task;
 * cancellation is best-effort (does not interrupt a running task unless
 * {@code mayInterruptIfRunning=true}).
 */
public interface ScheduledHandle {
    /** Cancel future executions. Does not interrupt a running task unless
     *  {@code mayInterruptIfRunning=true}. Idempotent. */
    boolean cancel(boolean mayInterruptIfRunning);
    /** Whether this handle has been cancelled. */
    boolean isCancelled();

    /** Package-private canonical implementation. */
    record Record(java.util.concurrent.ScheduledFuture<?> future) implements ScheduledHandle {
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            return future.cancel(mayInterruptIfRunning);
        }
        @Override public boolean isCancelled() { return future.isCancelled(); }
    }
}
