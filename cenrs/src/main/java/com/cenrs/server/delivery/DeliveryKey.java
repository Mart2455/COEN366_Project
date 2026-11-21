package com.cenrs.server.delivery;

import java.util.Objects;

public record DeliveryKey(
    long requestNumber,
    String incidentId,
    long version,
    String clientName
) {
    public DeliveryKey {
        if (requestNumber < 1) {
            throw new IllegalArgumentException(
                "request number must be positive"
            );
        }

        if (version < 1) {
            throw new IllegalArgumentException(
                "version must be positive"
            );
        }

        incidentId = requiredText(incidentId, "incident ID");
        clientName = requiredText(clientName, "client name");
    }

    private static String requiredText(
        String value,
        String fieldName
    ) {
        Objects.requireNonNull(
            value,
            fieldName + " cannot be null"
        );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                fieldName + " cannot be blank"
            );
        }

        return value;
    }
}