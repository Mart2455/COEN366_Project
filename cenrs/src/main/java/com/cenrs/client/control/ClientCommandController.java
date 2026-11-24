package com.cenrs.client.control;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.Incident;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.Severity;
import com.cenrs.contract.Subscription;
import com.cenrs.contract.port.TransportPort;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;
import com.cenrs.protocol.UdpMessageCodec;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

// Builds client commands and correlates their UDP responses.
// Socket receive loops belong to the client runtime, not this class.
public final class ClientCommandController {
    private final InetSocketAddress server;
    private final String advertisedIpAddress;
    private final int udpPort;
    private final int tcpPort;
    private final TransportPort transport;
    private final UdpMessageCodec codec;
    private final ClientControlState state;
    private final Duration responseTimeout;
    private final AtomicLong requestNumbers = new AtomicLong(1);
    private final ConcurrentMap<String, PendingRequest> pendingRequests =
            new ConcurrentHashMap<>();

    public ClientCommandController(
            InetSocketAddress server,
            String advertisedIpAddress,
            int udpPort,
            int tcpPort,
            TransportPort transport,
            UdpMessageCodec codec,
            ClientControlState state,
            Duration responseTimeout
    ) {
        this.server = Objects.requireNonNull(server);
        this.advertisedIpAddress = Objects.requireNonNull(
                advertisedIpAddress
        );
        this.udpPort = validPort(udpPort, "UDP port");
        this.tcpPort = validPort(tcpPort, "TCP port");
        this.transport = Objects.requireNonNull(transport);
        this.codec = Objects.requireNonNull(codec);
        this.state = Objects.requireNonNull(state);
        this.responseTimeout = requirePositive(responseTimeout);
    }

    public CompletableFuture<ControlMessage> register(
            String name,
            ClientRole role
    ) throws IOException, ProtocolException {
        if (state.profile().isPresent()) {
            throw new IllegalStateException(
                    "Client is already registered"
            );
        }

        ClientProfile requestedProfile = new ClientProfile(
                name,
                role,
                advertisedIpAddress,
                udpPort,
                tcpPort
        );

        ControlMessage request = ControlMessage.of(
                MessageType.REGISTER,
                nextRequestNumber(),
                requestedProfile.name(),
                requestedProfile.role().name(),
                requestedProfile.ipAddress(),
                Integer.toString(requestedProfile.udpPort()),
                Integer.toString(requestedProfile.tcpPort())
        );

        return sendAndAwait(
                request,
                MessageType.REGISTERED,
                MessageType.REGISTER_DENIED
        ).thenApply(response -> {
            if (response.type() == MessageType.REGISTERED) {
                state.recordRegistration(
                        requestedProfile,
                        parseLocations(response.field(1)),
                        parseCategories(response.field(2))
                );
            }

            return response;
        });
    }

    public ControlMessage deregister()
            throws IOException, ProtocolException {
        ClientProfile profile = state.requireProfile();
        ControlMessage request = ControlMessage.of(
                MessageType.DE_REGISTER,
                nextRequestNumber(),
                profile.name()
        );

        sendWithoutResponse(request);
        state.clearRegistration();
        return request;
    }

    public CompletableFuture<ControlMessage> subscribe(
            CampusLocation location,
            AlertCategory category
    ) throws IOException, ProtocolException {
        ClientProfile profile = state.requireProfile();

        if (!profile.role().canSubscribe()) {
            throw new IllegalStateException(
                    "Current role cannot subscribe"
            );
        }

        Subscription subscription = new Subscription(location, category);
        ControlMessage request = ControlMessage.of(
                MessageType.SUBSCRIBE,
                nextRequestNumber(),
                subscription.location().name(),
                subscription.category().name()
        );

        return sendAndAwait(
                request,
                MessageType.SUBSCRIBED,
                MessageType.SUBSCRIPTION_DENIED
        ).thenApply(response -> {
            if (response.type() == MessageType.SUBSCRIBED) {
                state.addSubscription(subscription);
            }

            return response;
        });
    }

    public ControlMessage unsubscribe(
            CampusLocation location,
            AlertCategory category
    ) throws IOException, ProtocolException {
        ClientProfile profile = state.requireProfile();

        if (!profile.role().canSubscribe()) {
            throw new IllegalStateException(
                    "Current role cannot unsubscribe"
            );
        }

        Subscription subscription = new Subscription(location, category);
        ControlMessage request = ControlMessage.of(
                MessageType.UNSUBSCRIBE,
                nextRequestNumber(),
                subscription.location().name(),
                subscription.category().name()
        );

        sendWithoutResponse(request);
        state.removeSubscription(subscription);
        return request;
    }

    public CompletableFuture<ControlMessage> publish(
            AlertCategory category,
            CampusLocation location,
            Severity severity,
            String title,
            String description,
            String attachment
    ) throws IOException, ProtocolException {
        ClientProfile profile = requirePublisher();

        Incident proposed = new Incident(
                "PENDING",
                1,
                category,
                location,
                severity,
                IncidentStatus.ACTIVE,
                title,
                description,
                attachment,
                profile.name()
        );

        ControlMessage request = ControlMessage.of(
                MessageType.PUBLISH_ALERT,
                nextRequestNumber(),
                proposed.category().name(),
                proposed.location().name(),
                proposed.severity().name(),
                proposed.title(),
                proposed.description(),
                proposed.attachment()
        );

        return sendAndAwait(
                request,
                MessageType.ALERT_ACCEPTED,
                MessageType.ALERT_DENIED
        ).thenApply(response -> {
            if (response.type() == MessageType.ALERT_ACCEPTED) {
                state.saveIncident(new Incident(
                        response.field(1),
                        proposed.version(),
                        proposed.category(),
                        proposed.location(),
                        proposed.severity(),
                        proposed.status(),
                        proposed.title(),
                        proposed.description(),
                        proposed.attachment(),
                        proposed.publisherName()
                ));
            }

            return response;
        });
    }

    public CompletableFuture<ControlMessage> update(
            String incidentId,
            Severity severity,
            String title,
            String description
    ) throws IOException, ProtocolException {
        ClientProfile profile = requirePublisher();
        Incident current = state.requireIncident(incidentId);
        requireOwnedActiveIncident(current, profile);

        Incident proposed = new Incident(
                current.incidentId(),
                current.version() + 1,
                current.category(),
                current.location(),
                severity,
                current.status(),
                title,
                description,
                current.attachment(),
                current.publisherName()
        );

        ControlMessage request = ControlMessage.of(
                MessageType.UPDATE_ALERT,
                nextRequestNumber(),
                proposed.incidentId(),
                proposed.severity().name(),
                proposed.title(),
                proposed.description()
        );

        return sendAndAwait(
                request,
                MessageType.ALERT_UPDATED,
                MessageType.ALERT_DENIED
        ).thenApply(response -> {
            if (response.type() == MessageType.ALERT_UPDATED) {
                requireMatchingIncident(
                        response,
                        current.incidentId()
                );

                state.saveIncident(new Incident(
                        proposed.incidentId(),
                        Long.parseLong(response.field(2)),
                        proposed.category(),
                        proposed.location(),
                        proposed.severity(),
                        proposed.status(),
                        proposed.title(),
                        proposed.description(),
                        proposed.attachment(),
                        proposed.publisherName()
                ));
            }

            return response;
        });
    }

    public CompletableFuture<ControlMessage> close(
            String incidentId,
            String reason
    ) throws IOException, ProtocolException {
        ClientProfile profile = requirePublisher();
        Incident current = state.requireIncident(incidentId);
        requireOwnedActiveIncident(current, profile);
        String requiredReason = requireText(reason, "reason");

        ControlMessage request = ControlMessage.of(
                MessageType.CLOSE_ALERT,
                nextRequestNumber(),
                current.incidentId(),
                requiredReason
        );

        return sendAndAwait(
                request,
                MessageType.ALERT_CLOSED,
                MessageType.ALERT_DENIED
        ).thenApply(response -> {
            if (response.type() == MessageType.ALERT_CLOSED) {
                requireMatchingIncident(
                        response,
                        current.incidentId()
                );

                state.saveIncident(new Incident(
                        current.incidentId(),
                        current.version() + 1,
                        current.category(),
                        current.location(),
                        current.severity(),
                        IncidentStatus.CLOSED,
                        current.title(),
                        current.description(),
                        current.attachment(),
                        current.publisherName()
                ));
            }

            return response;
        });
    }

    // Completes a pending command. Returns false for notifications or late
    // responses that do not belong to a current command.
    public boolean acceptResponse(ControlMessage response) {
        Objects.requireNonNull(response);

        PendingRequest pending = pendingRequests.remove(
                response.field(0)
        );

        if (pending == null) {
            return false;
        }

        if (!pending.expectedTypes().contains(response.type())) {
            pending.response().completeExceptionally(
                    new ProtocolException(
                            "Unexpected response "
                                    + response.type().wireName()
                    )
            );
        } else {
            pending.response().complete(response);
        }

        return true;
    }

    private CompletableFuture<ControlMessage> sendAndAwait(
            ControlMessage request,
            MessageType firstExpected,
            MessageType... otherExpected
    ) throws IOException, ProtocolException {
        String requestNumber = request.field(0);
        Set<MessageType> expectedTypes = EnumSet.of(
                firstExpected,
                otherExpected
        );
        CompletableFuture<ControlMessage> response =
                new CompletableFuture<>();
        PendingRequest pending = new PendingRequest(
                expectedTypes,
                response
        );

        if (pendingRequests.putIfAbsent(requestNumber, pending) != null) {
            throw new IllegalStateException(
                    "Request number is already pending"
            );
        }

        try {
            transport.send(server, codec.encode(request));
        } catch (IOException | ProtocolException exception) {
            pendingRequests.remove(requestNumber, pending);
            throw exception;
        }

        response.orTimeout(
                responseTimeout.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS
        ).whenComplete((ignored, failure) ->
                pendingRequests.remove(requestNumber, pending)
        );

        return response;
    }

    private void sendWithoutResponse(ControlMessage request)
            throws IOException, ProtocolException {
        transport.send(server, codec.encode(request));
    }

    private String nextRequestNumber() {
        return Long.toString(requestNumbers.getAndIncrement());
    }

    private static List<CampusLocation> parseLocations(String values) {
        return Arrays.stream(values.split(","))
                .map(CampusLocation::valueOf)
                .toList();
    }

    private static List<AlertCategory> parseCategories(String values) {
        return Arrays.stream(values.split(","))
                .map(AlertCategory::valueOf)
                .toList();
    }

    private ClientProfile requirePublisher() {
        ClientProfile profile = state.requireProfile();

        if (!profile.role().canPublish()) {
            throw new IllegalStateException(
                    "Current role cannot publish"
            );
        }

        return profile;
    }

    private static void requireOwnedActiveIncident(
            Incident incident,
            ClientProfile profile
    ) {
        if (!incident.publisherName().equals(profile.name())) {
            throw new IllegalStateException(
                    "Only the original publisher can change this incident"
            );
        }

        if (incident.status() != IncidentStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Incident is already closed"
            );
        }
    }

    private static void requireMatchingIncident(
            ControlMessage response,
            String expectedIncidentId
    ) {
        if (!response.field(1).equals(expectedIncidentId)) {
            throw new IllegalStateException(
                    "Response incident ID does not match request"
            );
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " cannot be null");

        if (value.isBlank()
                || value.contains("|")
                || value.contains("\r")
                || value.contains("\n")) {
            throw new IllegalArgumentException(
                    name + " is blank or contains a reserved character"
            );
        }

        return value;
    }

    private static int validPort(int port, String name) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    name + " must be between 1 and 65535"
            );
        }

        return port;
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout);

        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "Response timeout must be positive"
            );
        }

        return timeout;
    }

    private record PendingRequest(
            Set<MessageType> expectedTypes,
            CompletableFuture<ControlMessage> response
    ) {
        private PendingRequest {
            expectedTypes = Set.copyOf(expectedTypes);
            Objects.requireNonNull(response);
        }
    }
}