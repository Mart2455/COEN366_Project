package com.cenrs.server.delivery;

import com.cenrs.contract.port.DeliverySchedulerPort;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ExecutorDeliveryScheduler
    implements DeliverySchedulerPort, AutoCloseable {

    private final ScheduledThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ExecutorDeliveryScheduler() {
        this(2);
    }

    public ExecutorDeliveryScheduler(int threadCount) {
        if (threadCount < 1) {
            throw new IllegalArgumentException(
                "thread count must be positive"
            );
        }

        executor = new ScheduledThreadPoolExecutor(threadCount);
        executor.setRemoveOnCancelPolicy(true);
    }

    @Override
    public ScheduledDelivery schedule(
        Duration delay,
        Runnable task
    ) {
        Objects.requireNonNull(delay, "delay cannot be null");
        Objects.requireNonNull(task, "task cannot be null");

        if (delay.isNegative()) {
            throw new IllegalArgumentException(
                "delay cannot be negative"
            );
        }

        if (closed.get()) {
            throw new IllegalStateException(
                "delivery scheduler is closed"
            );
        }

        ScheduledFuture<?> future = executor.schedule(
            task,
            delay.toNanos(),
            TimeUnit.NANOSECONDS
        );

        return new ScheduledTask(future);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    private static final class ScheduledTask
        implements ScheduledDelivery {

        private final ScheduledFuture<?> future;

        private ScheduledTask(ScheduledFuture<?> future) {
            this.future = future;
        }

        @Override
        public boolean cancel() {
            return future.cancel(false);
        }

        @Override
        public boolean isCancelled() {
            return future.isCancelled();
        }
    }
}