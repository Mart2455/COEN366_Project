package com.cenrs.server.delivery;

import java.time.Instant;
import java.util.Objects;

public record DeliveryState(
    DeliveryKey key,
    Status status,
    int attempts,
    Instant updatedAt
) {
    public DeliveryState {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(
            updatedAt,
            "updated time cannot be null"
        );

        if (attempts < 1 || attempts > 3) {
            throw new IllegalArgumentException(
                "attempts must be between 1 and 3"
            );
        }
    }

    public enum Status {
        PENDING,
        CONFIRMED,
        UNCONFIRMED
    }
}