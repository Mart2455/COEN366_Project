package com.cenrs.server.incident;

import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.Incident;
import com.cenrs.contract.port.IncidentEventSinkPort;
import com.cenrs.contract.port.IncidentStorePort;
import com.cenrs.contract.port.TransportPort;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;
import com.cenrs.protocol.UdpMessageCodec;
import com.cenrs.server.subscription.SubscriptionService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Sends the initial UDP ALERT notification for each newly created incident. */
public final class IncidentNotificationRouter
        implements IncidentEventSinkPort {

    private final SubscriptionService subscriptions;
    private final IncidentStorePort incidents;
    private final TransportPort transport;
    private final UdpMessageCodec codec;
    private final IncidentEventSinkPort observer;
    private final AtomicLong requestNumbers = new AtomicLong(1);

    public IncidentNotificationRouter(
            SubscriptionService subscriptions,
            IncidentStorePort incidents,
            TransportPort transport,
            UdpMessageCodec codec,
            IncidentEventSinkPort observer
    ) {
        this.subscriptions = Objects.requireNonNull(subscriptions);
        this.incidents = Objects.requireNonNull(incidents);
        this.transport = Objects.requireNonNull(transport);
        this.codec = Objects.requireNonNull(codec);
        this.observer = Objects.requireNonNull(observer);
    }

    @Override
    public void onCreated(Incident incident) {
        Set<ClientProfile> recipients =
                subscriptions.findMatchingSubscribers(
                        incident.location(),
                        incident.category()
                );

        incidents.recordRecipients(
                incident.incidentId(),
                recipients.stream()
                        .map(ClientProfile::name)
                        .collect(Collectors.toUnmodifiableSet())
        );

        recipients.stream()
                .sorted(Comparator.comparing(ClientProfile::name))
                .forEach(recipient -> sendAlert(recipient, incident));

        observer.onCreated(incident);
    }

    private void sendAlert(
            ClientProfile recipient,
            Incident incident
    ) {
        String requestNumber = nextRequestNumber();
        ControlMessage alert = ControlMessage.of(
                MessageType.ALERT,
                requestNumber,
                incident.incidentId(),
                Long.toString(incident.version()),
                incident.category().name(),
                incident.location().name(),
                incident.severity().name(),
                incident.title(),
                incident.description(),
                incident.attachment()
        );

        try {
            transport.send(
                    new InetSocketAddress(
                            recipient.ipAddress(),
                            recipient.udpPort()
                    ),
                    codec.encode(alert)
            );
        } catch (IOException | ProtocolException exception) {
            System.err.println(
                    "[ALERT DISTRIBUTION ERROR] "
                            + recipient.name()
                            + ": "
                            + exception.getMessage()
            );
        }
    }

    private String nextRequestNumber() {
        return Long.toString(
                requestNumbers.getAndUpdate(Math::incrementExact)
        );
    }

    @Override
    public void onUpdated(Incident incident) {
        observer.onUpdated(incident);
    }

    @Override
    public void onClosed(Incident incident, String reason) {
        observer.onClosed(incident, reason);
    }
}
