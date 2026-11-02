package io.github.cpearl0.blockagent.core;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** A monotonic deadline and cooperative cancellation, shared by all calls of one task. */
public final class CancellationToken implements AutoCloseable {
    private static final ScheduledExecutorService DEADLINES =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread thread = new Thread(r, "blockagent-deadlines");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final long deadlineNanos;
    private final CancellationToken parent;
    private final AutoCloseable parentRegistration;
    private volatile ScheduledFuture<?> deadlineTask;

    public CancellationToken(long timeoutMs) {
        this(null, timeoutMs);
    }

    public CancellationToken(CancellationToken parent, long timeoutMs) {
        if (timeoutMs < 1 || timeoutMs > 86_400_000L)
            throw new IllegalArgumentException("Invalid cancellation deadline");
        this.parent = parent;
        this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        this.deadlineTask = DEADLINES.schedule(this::cancel, timeoutMs, TimeUnit.MILLISECONDS);
        this.parentRegistration = parent == null ? () -> {} : parent.onCancel(this::cancel);
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        ScheduledFuture<?> task = deadlineTask;
        if (task != null) task.cancel(false);
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
            }
        }
        listeners.clear();
    }

    public boolean isCancelled() {
        if (!cancelled.get()
                && (System.nanoTime() >= deadlineNanos || (parent != null && parent.isCancelled())))
            cancel();
        return cancelled.get();
    }

    public long remainingMillis() {
        if (isCancelled()) return 0;
        long own = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
        return parent == null ? own : Math.min(own, parent.remainingMillis());
    }

    public void throwIfCancelled() {
        if (isCancelled()) throw new CancellationException("Task cancelled or deadline exceeded");
    }

    /** Identifies the original command across per-action child deadlines. */
    public CancellationToken root() {
        return parent == null ? this : parent.root();
    }

    public AutoCloseable onCancel(Runnable listener) {
        if (isCancelled()) {
            listener.run();
            return () -> {};
        }
        listeners.add(listener);
        if (isCancelled() && listeners.remove(listener)) listener.run();
        return () -> listeners.remove(listener);
    }

    public <T> T await(CompletableFuture<T> future) throws Exception {
        while (true) {
            throwIfCancelled();
            try {
                T result = future.get(Math.min(100, remainingMillis()), TimeUnit.MILLISECONDS);
                throwIfCancelled();
                return result;
            } catch (TimeoutException ignored) {
            } catch (ExecutionException ex) {
                throwIfCancelled();
                if (ex.getCause() instanceof Exception e) throw e;
                throw new IllegalStateException("Asynchronous action failed", ex.getCause());
            }
        }
    }

    @Override
    public void close() {
        cancel();
        try {
            parentRegistration.close();
        } catch (Exception ignored) {
        }
    }
}
