package com.clawkit.ops.loop.automation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Test-only scheduler that executes tasks synchronously on demand.
 *
 * <p>Each call to {@link #tick()} fires all registered (non-cancelled)
 * tasks exactly once, regardless of their configured delay. The initial
 * delay is honored only if it equals the first configured period.
 * This matches what test code expects: one tick = one execution cycle.
 */
public final class ManualAutomationTaskScheduler implements AutomationTaskScheduler {

    private final List<TaskEntry> tasks = new ArrayList<>();
    private boolean closed;

    @Override
    public synchronized ScheduledHandle schedule(
        Runnable task, long initialDelay, long delay, TimeUnit unit
    ) {
        if (closed) throw new IllegalStateException("scheduler closed");
        TaskEntry entry = new TaskEntry(task);
        tasks.add(entry);
        return entry;
    }

    /** Fire all non-cancelled tasks once. */
    public synchronized void tick() {
        for (TaskEntry entry : List.copyOf(tasks)) {
            if (entry.cancelled) continue;
            try {
                entry.task.run();
            } catch (Exception e) {
                // Swallow — real scheduler would log and continue
            }
        }
    }

    /** Simulate N ticks. */
    public void tick(int count) {
        for (int i = 0; i < count; i++) tick();
    }

    /** Number of registered (non-cancelled) tasks. */
    public synchronized int taskCount() {
        return (int) tasks.stream().filter(t -> !t.cancelled).count();
    }

    @Override
    public synchronized void close() {
        closed = true;
        tasks.clear();
    }

    // ── Internal ──────────────────────────────────────────────────

    static final class TaskEntry implements ScheduledHandle {
        final Runnable task;
        volatile boolean cancelled;

        TaskEntry(Runnable task) {
            this.task = task;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean was = cancelled;
            cancelled = true;
            return !was;
        }

        @Override
        public boolean isCancelled() { return cancelled; }
    }
}
