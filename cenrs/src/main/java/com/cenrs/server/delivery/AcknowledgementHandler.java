package com.cenrs.server.delivery;

import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;

import java.util.Objects;

public final class AcknowledgementHandler {
    private final AcknowledgementScheduler acknowledgements;

    public AcknowledgementHandler(
        AcknowledgementScheduler acknowledgements
    ) {
        this.acknowledgements = Objects.requireNonNull(
            acknowledgements,
            "acknowledgements cannot be null"
        );
    }

    public boolean handle(ControlMessage message) {
        Objects.requireNonNull(message, "message cannot be null");

        if (message.type() != MessageType.ALERT_ACK) {
            return false;
        }

        try {
            DeliveryKey key = new DeliveryKey(
                Long.parseLong(message.field(0)),
                message.field(1),
                Long.parseLong(message.field(2)),
                message.field(3)
            );

            return acknowledgements.acknowledge(key);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}