package com.cenrs.server.delivery;

import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.port.RegistryPort;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;

public final class AcknowledgementHandler {
    private final AcknowledgementScheduler acknowledgements;
    private final RegistryPort registry;

    public AcknowledgementHandler(
        AcknowledgementScheduler acknowledgements,
        RegistryPort registry
    ) {
        this.acknowledgements = Objects.requireNonNull(
            acknowledgements,
            "acknowledgements cannot be null"
        );
        this.registry = Objects.requireNonNull(
            registry,
            "registry cannot be null"
        );
    }

    public boolean handle(
        ControlMessage message,
        InetSocketAddress sender
    ) {
        Objects.requireNonNull(message, "message cannot be null");
        Objects.requireNonNull(sender, "sender cannot be null");

        if (message.type() != MessageType.ALERT_ACK) {
            return false;
        }

        Optional<DeliveryKey> key = parseKey(message);
        if (key.isEmpty()) {
            return false;
        }

        Optional<ClientProfile> client =
                registry.findByName(key.get().clientName());
        if (
            client.isEmpty()
            || !registry.isActive(key.get().clientName())
            || !client.get().role().canSubscribe()
            || sender.getAddress() == null
            || !client.get().ipAddress().equals(
                sender.getAddress().getHostAddress()
            )
            || client.get().udpPort() != sender.getPort()
        ) {
            return false;
        }

        return acknowledgements.acknowledge(key.get());
    }

    private Optional<DeliveryKey> parseKey(ControlMessage message) {
        try {
            return Optional.of(new DeliveryKey(
                Long.parseLong(message.field(0)),
                message.field(1),
                Long.parseLong(message.field(2)),
                message.field(3)
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}