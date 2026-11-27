package com.cenrs.server.subscription;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class SubscriptionService {
    private final RegistryPort registry;
    private final SubscriptionPort subscriptions;
    private final TransportPort transport;
    private final UdpMessageCodec codec;

    public SubscriptionService(
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

    // Handles only the subscription messages assigned to this service.
    public void handle(
            ControlMessage request,
            InetSocketAddress sender
    ) throws IOException, ProtocolException {

        Objects.requireNonNull(request);
        Objects.requireNonNull(sender);

        switch (request.type()) {
            case SUBSCRIBE ->
                    send(sender, subscribe(request, sender));

            case UNSUBSCRIBE ->
                    unsubscribe(request, sender);

            default -> throw new IllegalArgumentException(
                    "Subscription service cannot handle "
                            + request.type().wireName()
            );
        }
    }

    private ControlMessage subscribe(
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

        Optional<ClientProfile> senderProfile =
                findClient(sender);

        if (senderProfile.isEmpty()) {
            return denied(
                    requestNumber,
                    "Client is not registered"
            );
        }

        ClientProfile client = senderProfile.get();

        if (!client.role().canSubscribe()) {
            return denied(
                    requestNumber,
                    "Role cannot subscribe"
            );
        }

        Subscription subscription;

        try {
            subscription = parseSubscription(request);
        } catch (IllegalArgumentException exception) {
            return denied(
                    requestNumber,
                    exception.getMessage()
            );
        }

        // Repeating SUBSCRIBE returns success without storing another copy.
        subscriptions.add(
                client.name(),
                subscription
        );

        return ControlMessage.of(
                MessageType.SUBSCRIBED,
                requestNumber
        );
    }

    private void unsubscribe(
            ControlMessage request,
            InetSocketAddress sender
    ) {
        if (!isPositiveRequestNumber(request.field(0))) {
            return;
        }

        Optional<ClientProfile> senderProfile =
                findClient(sender);

        if (
                senderProfile.isEmpty()
                || !senderProfile.get().role().canSubscribe()
        ) {
            return;
        }

        try {
            Subscription subscription =
                    parseSubscription(request);

            // Removing a missing pair is intentionally harmless.
            subscriptions.remove(
                    senderProfile.get().name(),
                    subscription
            );
        } catch (IllegalArgumentException exception) {
            // UNSUBSCRIBE has no denial response in the protocol.
        }
    }

    // Returns each client once even when several subscriptions match.
    public Set<ClientProfile> findMatchingSubscribers(
            CampusLocation alertLocation,
            AlertCategory alertCategory
    ) {
        Objects.requireNonNull(alertLocation);
        Objects.requireNonNull(alertCategory);

        return registry.findAll()
                .stream()
                .filter(
                        client ->
                                client.role().canSubscribe()
                )
                .filter(client -> registry.isActive(client.name()))
                .filter(
                        client ->
                                subscriptions
                                        .findByClient(client.name())
                                        .stream()
                                        .anyMatch(
                                                subscription ->
                                                        subscription.matches(
                                                                alertLocation,
                                                                alertCategory
                                                        )
                                        )
                )
                .collect(Collectors.toUnmodifiableSet());
    }

    private Subscription parseSubscription(
            ControlMessage request
    ) {
        CampusLocation location;

        try {
            location = CampusLocation.valueOf(
                    request.field(1)
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid location"
            );
        }

        AlertCategory category;

        try {
            category = AlertCategory.valueOf(
                    request.field(2)
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid category"
            );
        }

        return new Subscription(location, category);
    }

    // SUBSCRIBE has no name field, so the UDP endpoint identifies the client.
    private Optional<ClientProfile> findClient(
            InetSocketAddress sender
    ) {
        return registry.findAll()
                .stream()
                .filter(
                        client ->
                                sameEndpoint(client, sender)
                )
                .findFirst();
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

    private void send(
            InetSocketAddress destination,
            ControlMessage response
    ) throws IOException, ProtocolException {

        transport.send(
                destination,
                codec.encode(response)
        );
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
                MessageType.SUBSCRIPTION_DENIED,
                requestNumber,
                reason
        );
    }
}