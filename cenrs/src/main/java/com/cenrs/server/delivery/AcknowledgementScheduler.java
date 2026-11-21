package com.cenrs.server.delivery;

import com.cenrs.contract.port.DeliverySchedulerPort;
import com.cenrs.contract.port.TransportPort;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AcknowledgementScheduler
    implements AutoCloseable {

    public static final Duration DEFAULT_TIMEOUT =
        Duration.ofSeconds(2);

    private static final int MAX_ATTEMPTS = 3;

    private final Clock clock;
    private final DeliverySchedulerPort scheduler;
    private final DeliveryStateRepository states;
    private final TransportPort transport;
    private final Duration timeout;

    private final ConcurrentMap<DeliveryKey, PendingDelivery> pending =
        new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean();

    public AcknowledgementScheduler(
        Clock clock,
        DeliverySchedulerPort scheduler,
        DeliveryStateRepository states,
        TransportPort transport
    ) {
        this(
            clock,
            scheduler,
            states,
            transport,
            DEFAULT_TIMEOUT
        );
    }

    public AcknowledgementScheduler(
        Clock clock,
        DeliverySchedulerPort scheduler,
        DeliveryStateRepository states,
        TransportPort transport,
        Duration timeout
    ) {
        this.clock = Objects.requireNonNull(clock);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.states = Objects.requireNonNull(states);
        this.transport = Objects.requireNonNull(transport);
        this.timeout = requirePositive(timeout);
    }

    public void sendReliably(
        DeliveryKey key,
        InetSocketAddress destination,
        byte[] payload
    ) throws IOException {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(
            destination,
            "destination cannot be null"
        );
        Objects.requireNonNull(payload, "payload cannot be null");

        if (payload.length == 0) {
            throw new IllegalArgumentException(
                "payload cannot be empty"
            );
        }

        if (closed.get()) {
            throw new IllegalStateException(
                "acknowledgement scheduler is closed"
            );
        }

        PendingDelivery delivery = new PendingDelivery(
            destination,
            payload
        );

        if (pending.putIfAbsent(key, delivery) != null) {
            throw new IllegalStateException(
                "delivery is already pending"
            );
        }

        try {
            sendAttempt(key, delivery);
        } catch (IOException | RuntimeException exception) {
            pending.remove(key, delivery);
            throw exception;
        }
    }

    public boolean acknowledge(DeliveryKey key) {
        Objects.requireNonNull(key, "key cannot be null");

        PendingDelivery delivery = pending.get(key);
        if (delivery == null) {
            return false;
        }

        synchronized (delivery) {
            if (!pending.remove(key, delivery)) {
                return false;
            }

            if (delivery.timer != null) {
                delivery.timer.cancel();
            }

            saveState(
                key,
                DeliveryState.Status.CONFIRMED,
                delivery.attempts
            );
            return true;
        }
    }

    public int pendingCount() {
        return pending.size();
    }

    private void sendAttempt(
        DeliveryKey key,
        PendingDelivery delivery
    ) throws IOException {
        synchronized (delivery) {
            delivery.attempts++;

            transport.send(
                delivery.destination,
                delivery.payload.clone()
            );

            saveState(
                key,
                DeliveryState.Status.PENDING,
                delivery.attempts
            );

            delivery.timer = scheduler.schedule(
                timeout,
                () -> handleTimeout(key, delivery)
            );
        }
    }

    private void handleTimeout(
        DeliveryKey key,
        PendingDelivery delivery
    ) {
        synchronized (delivery) {
            if (
                closed.get()
                || pending.get(key) != delivery
            ) {
                return;
            }

            if (delivery.attempts == MAX_ATTEMPTS) {
                pending.remove(key, delivery);
                saveState(
                    key,
                    DeliveryState.Status.UNCONFIRMED,
                    delivery.attempts
                );
                return;
            }

            try {
                sendAttempt(key, delivery);
            } catch (IOException exception) {
                // A failed UDP send still counts as an attempt.
                saveState(
                    key,
                    DeliveryState.Status.PENDING,
                    delivery.attempts
                );

                delivery.timer = scheduler.schedule(
                    timeout,
                    () -> handleTimeout(key, delivery)
                );
            }
        }
    }

    private void saveState(
        DeliveryKey key,
        DeliveryState.Status status,
        int attempts
    ) {
        states.save(
            new DeliveryState(
                key,
                status,
                attempts,
                clock.instant()
            )
        );
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout cannot be null");

        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                "timeout must be positive"
            );
        }

        return timeout;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        pending.forEach((key, delivery) -> {
            synchronized (delivery) {
                if (
                    pending.remove(key, delivery)
                    && delivery.timer != null
                ) {
                    delivery.timer.cancel();
                }
            }
        });
    }

    private static final class PendingDelivery {
        private final InetSocketAddress destination;
        private final byte[] payload;

        private int attempts;
        private DeliverySchedulerPort.ScheduledDelivery timer;

        private PendingDelivery(
            InetSocketAddress destination,
            byte[] payload
        ) {
            this.destination = destination;
            this.payload = payload.clone();
        }
    }
}