package com.cenrs.server.registration;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.Subscription;
import com.cenrs.contract.port.RegistryPort;
import com.cenrs.contract.port.SubscriptionPort;
import com.cenrs.contract.port.TransportPort;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;
import com.cenrs.protocol.UdpMessageCodec;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

public final class RegistrationService {
    private static final String LOCATION_LIST =
            enumList(CampusLocation.values());

    private static final String CATEGORY_LIST =
            enumList(AlertCategory.values());

    private final RegistryPort registry;
    private final SubscriptionPort subscriptions;
    private final TransportPort transport;
    private final UdpMessageCodec codec;

    public RegistrationService(
            RegistryPort registry,
            SubscriptionPort subscriptions,
            TransportPort transport,
            UdpMessageCodec codec
    ) {
        this.registry = Objects.requireNonNull(registry);
        this.subscriptions = Objects.requireNonNull(subscriptions);
        this.transport = Objects.requireNonNull(transport);
        this.codec = Objects.requireNonNull(codec);
    }

    public void handle(
            ControlMessage request,
            InetSocketAddress sender
    ) throws IOException, ProtocolException {

        Objects.requireNonNull(request);
        Objects.requireNonNull(sender);

        switch (request.type()) {
            case REGISTER ->
                    send(sender, register(request, sender));

            case DE_REGISTER ->
                    deregister(request, sender);

            default -> throw new IllegalArgumentException(
                    "Registration service cannot handle "
                            + request.type().wireName()
            );
        }
    }

    private ControlMessage register(
            ControlMessage request,
            InetSocketAddress sender
    ) {
        String requestNumber = request.field(0);

        if (!isPositiveRequestNumber(requestNumber)) {
            return denied(
                    requestNumber,
                    "Invalid request number"
            );
        }

        ClientRole role;

        try {
            role = ClientRole.valueOf(request.field(2));
        } catch (IllegalArgumentException exception) {
            return denied(requestNumber, "Invalid role");
        }

        int udpPort;
        int tcpPort;

        try {
            udpPort = parsePort(request.field(4), "UDP port");
            tcpPort = parsePort(request.field(5), "TCP port");
        } catch (IllegalArgumentException exception) {
            return denied(
                    requestNumber,
                    exception.getMessage()
            );
        }

        ClientProfile client;

        try {
            client = new ClientProfile(
                    request.field(1),
                    role,
                    request.field(3),
                    udpPort,
                    tcpPort
            );
        } catch (
                IllegalArgumentException
                | NullPointerException exception
        ) {
            return denied(
                    requestNumber,
                    "Malformed registration request"
            );
        }

        // The advertised UDP endpoint must be the real datagram sender.
        if (!sameEndpoint(client, sender)) {
            return denied(
                    requestNumber,
                    "Endpoint does not match sender"
            );
        }

        // A persisted client that registers again after a restart is reactivated.
        if (
                !registry.addIfAbsent(client)
                && !registry.reactivate(client)
        ) {
            return denied(
                    requestNumber,
                    "Name already exists"
            );
        }

        return ControlMessage.of(
                MessageType.REGISTERED,
                requestNumber,
                LOCATION_LIST,
                CATEGORY_LIST
        );
    }

    private void deregister(
            ControlMessage request,
            InetSocketAddress sender
    ) {
        if (!isPositiveRequestNumber(request.field(0))) {
            return;
        }

        String name = request.field(1);

        registry.findByName(name).ifPresent(client -> {
            if (!sameEndpoint(client, sender)) {
                return;
            }

            if (!registry.remove(name)) {
                return;
            }

            // Reusing the name later must start with no old subscriptions.
            for (
                    Subscription subscription
                    : subscriptions.findByClient(name)
            ) {
                subscriptions.remove(name, subscription);
            }
        });
    }

    private void send(
            InetSocketAddress destination,
            ControlMessage response
    ) throws IOException, ProtocolException {

        transport.send(
                destination,
                codec.encode(response)
        );
    }

    private boolean sameEndpoint(
            ClientProfile client,
            InetSocketAddress sender
    ) {
        return sender.getAddress() != null
                && sender.getAddress()
                        .getHostAddress()
                        .equals(client.ipAddress())
                && sender.getPort() == client.udpPort();
    }

    private static int parsePort(
            String value,
            String fieldName
    ) {
        try {
            int port = Integer.parseInt(value);

            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }

            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Invalid " + fieldName
            );
        }
    }

    private static boolean isPositiveRequestNumber(
            String value
    ) {
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static ControlMessage denied(
            String requestNumber,
            String reason
    ) {
        return ControlMessage.of(
                MessageType.REGISTER_DENIED,
                requestNumber,
                reason
        );
    }

    private static String enumList(Enum<?>[] values) {
        return Arrays.stream(values)
                .map(Enum::name)
                .collect(Collectors.joining(","));
    }
}