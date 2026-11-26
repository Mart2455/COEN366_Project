package com.cenrs.client.control;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.IncidentVersion;
import com.cenrs.contract.Severity;
import com.cenrs.contract.Subscription;
import com.cenrs.network.UdpTransport;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;
import com.cenrs.protocol.UdpMessageCodec;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class ClientController
        implements AutoCloseable {

    private static final long RESPONSE_TIMEOUT_SECONDS = 3;

    private final DatagramSocket socket;
    private final InetSocketAddress server;
    private final String advertisedIpAddress;
    private final int tcpPort;

    private final UdpMessageCodec codec =
            new UdpMessageCodec();

    private final UdpTransport transport;
    private final Consumer<ControlMessage> alertConsumer;

    private final AtomicLong requestNumbers =
            new AtomicLong(1);

    private final ConcurrentHashMap<
            String,
            CompletableFuture<ControlMessage>
            > pendingResponses =
            new ConcurrentHashMap<>();

    private final Set<Subscription> subscriptions =
            ConcurrentHashMap.newKeySet();

    private final Set<IncidentVersion> receivedAlerts =
            ConcurrentHashMap.newKeySet();

    private final Thread receiverThread;

    private volatile boolean running = true;
    private volatile ClientProfile profile;

    private volatile List<CampusLocation>
            availableLocations = List.of();

    private volatile List<AlertCategory>
            availableCategories = List.of();

    public ClientController(
            InetSocketAddress server,
            int localUdpPort,
            int tcpPort,
            String advertisedIpAddress
    ) throws SocketException {
        this(
                server,
                localUdpPort,
                tcpPort,
                advertisedIpAddress,
                ClientController::displayAlert
        );
    }

    public ClientController(
            InetSocketAddress server,
            int localUdpPort,
            int tcpPort,
            String advertisedIpAddress,
            Consumer<ControlMessage> alertConsumer
    ) throws SocketException {

        this.server = server;
        this.tcpPort = tcpPort;
        this.advertisedIpAddress =
                advertisedIpAddress;
        this.alertConsumer =
                java.util.Objects.requireNonNull(alertConsumer);

        socket = new DatagramSocket(localUdpPort);
        transport = new UdpTransport(socket);

        // Responses are received separately from interactive client commands.
        receiverThread = Thread
                .ofVirtual()
                .name("cenrs-client-receiver")
                .start(this::receiveLoop);
    }

    public ControlMessage register(
            String name,
            ClientRole role
    ) throws
            IOException,
            ProtocolException,
            TimeoutException,
            InterruptedException {

        ClientProfile requestedProfile =
                new ClientProfile(
                        name,
                        role,
                        advertisedIpAddress,
                        socket.getLocalPort(),
                        tcpPort
                );

        String requestNumber =
                nextRequestNumber();

        ControlMessage response = sendAndAwait(
                ControlMessage.of(
                        MessageType.REGISTER,
                        requestNumber,
                        requestedProfile.name(),
                        requestedProfile.role().name(),
                        requestedProfile.ipAddress(),
                        Integer.toString(
                                requestedProfile.udpPort()
                        ),
                        Integer.toString(
                                requestedProfile.tcpPort()
                        )
                )
        );

        if (response.type() == MessageType.REGISTERED) {
            profile = requestedProfile;

            availableLocations =
                    parseLocations(response.field(1));

            availableCategories =
                    parseCategories(response.field(2));
        } else if (
                response.type()
                != MessageType.REGISTER_DENIED
        ) {
            throw new ProtocolException(
                    "Unexpected registration response "
                            + response.type().wireName()
            );
        }

        return response;
    }

    public void deregister()
            throws IOException, ProtocolException {

        ClientProfile current = requireProfile();

        sendWithoutResponse(
                ControlMessage.of(
                        MessageType.DE_REGISTER,
                        nextRequestNumber(),
                        current.name()
                )
        );

        // DE-REGISTER has no confirmation, so local state is cleared after sending.
        profile = null;
        subscriptions.clear();
        availableLocations = List.of();
        availableCategories = List.of();
    }

    public ControlMessage subscribe(
            CampusLocation location,
            AlertCategory category
    ) throws
            IOException,
            ProtocolException,
            TimeoutException,
            InterruptedException {

        ClientProfile current = requireProfile();

        if (!current.role().canSubscribe()) {
            throw new IllegalStateException(
                    "Current role cannot subscribe"
            );
        }

        String requestNumber =
                nextRequestNumber();

        ControlMessage response = sendAndAwait(
                ControlMessage.of(
                        MessageType.SUBSCRIBE,
                        requestNumber,
                        location.name(),
                        category.name()
                )
        );

        if (response.type() == MessageType.SUBSCRIBED) {
            subscriptions.add(
                    new Subscription(location, category)
            );
        } else if (
                response.type()
                != MessageType.SUBSCRIPTION_DENIED
        ) {
            throw new ProtocolException(
                    "Unexpected subscription response "
                            + response.type().wireName()
            );
        }

        return response;
    }

    public void unsubscribe(
            CampusLocation location,
            AlertCategory category
    ) throws IOException, ProtocolException {

        requireProfile();

        Subscription subscription =
                new Subscription(location, category);

        sendWithoutResponse(
                ControlMessage.of(
                        MessageType.UNSUBSCRIBE,
                        nextRequestNumber(),
                        location.name(),
                        category.name()
                )
        );

        // UNSUBSCRIBE has no confirmation in the Phase One protocol.
        subscriptions.remove(subscription);
    }

    public Optional<ClientProfile> profile() {
        return Optional.ofNullable(profile);
    }

    public Set<Subscription> subscriptions() {
        return Set.copyOf(subscriptions);
    }

    public List<CampusLocation> availableLocations() {
        return List.copyOf(availableLocations);
    }

    public List<AlertCategory> availableCategories() {
        return List.copyOf(availableCategories);
    }

    public int localUdpPort() {
        return socket.getLocalPort();
    }

    private ControlMessage sendAndAwait(
            ControlMessage request
    ) throws
            IOException,
            ProtocolException,
            TimeoutException,
            InterruptedException {

        String requestNumber = request.field(0);

        CompletableFuture<ControlMessage> response =
                new CompletableFuture<>();

        pendingResponses.put(
                requestNumber,
                response
        );

        try {
            transport.send(
                    server,
                    codec.encode(request)
            );

            return response.get(
                    RESPONSE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (ExecutionException exception) {
            throw new IOException(
                    "Could not receive server response",
                    exception.getCause()
            );
        } finally {
            pendingResponses.remove(
                    requestNumber,
                    response
            );
        }
    }

    private void sendWithoutResponse(
            ControlMessage request
    ) throws IOException, ProtocolException {

        transport.send(
                server,
                codec.encode(request)
        );
    }

    private void receiveLoop() {
        while (running) {
            byte[] buffer = new byte[
                    UdpMessageCodec.MAX_DATAGRAM_BYTES + 1
            ];

            DatagramPacket packet =
                    new DatagramPacket(
                            buffer,
                            buffer.length
                    );

            try {
                socket.receive(packet);

                if (
                        !packet.getAddress().equals(
                                server.getAddress()
                        )
                        || packet.getPort()
                                != server.getPort()
                ) {
                    continue;
                }

                byte[] datagram = Arrays.copyOfRange(
                        packet.getData(),
                        packet.getOffset(),
                        packet.getOffset()
                                + packet.getLength()
                );

                ControlMessage response =
                        codec.decode(datagram);

                if (response.type() == MessageType.ALERT) {
                    receiveAlert(response);
                    continue;
                }

                System.out.println(
                        "[UDP RECEIVED] "
                                + server
                                + " "
                                + response.type().wireName()
                                + " "
                                + response.fields()
                );

                CompletableFuture<ControlMessage>
                        pending =
                        pendingResponses.remove(
                                response.field(0)
                        );

                if (pending != null) {
                    pending.complete(response);
                } else {
                    System.err.println(
                            "[CLIENT IGNORED] No pending request "
                                    + response.field(0)
                    );
                }
            } catch (SocketException exception) {
                if (running) {
                    System.err.println(
                            "[CLIENT ERROR] "
                                    + exception.getMessage()
                    );
                }

                return;
            } catch (
                    IOException
                    | ProtocolException
                    | RuntimeException exception
            ) {
                System.err.println(
                        "[CLIENT ERROR] "
                                + exception.getMessage()
                );
            }
        }
    }

    private void receiveAlert(ControlMessage alert)
            throws ProtocolException {
        IncidentVersion incidentVersion;
        Severity severity;

        try {
            long requestNumber = Long.parseLong(alert.field(0));
            if (requestNumber < 1) {
                throw new IllegalArgumentException(
                        "Alert request number must be positive"
                );
            }

            incidentVersion = new IncidentVersion(
                    alert.field(1),
                    Long.parseLong(alert.field(2))
            );
            AlertCategory.valueOf(alert.field(3));
            CampusLocation.valueOf(alert.field(4));
            severity = Severity.valueOf(alert.field(5));
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(
                    "Invalid ALERT message: " + exception.getMessage(),
                    exception
            );
        }

        if (severity.requiresAcknowledgement()) {
            ClientProfile current = profile;
            if (current == null || !current.role().canSubscribe()) {
                System.err.println(
                        "[CLIENT ERROR] Cannot acknowledge critical alert "
                                + incidentVersion.incidentId()
                                + " without a registered subscriber profile"
                );
            } else {
                try {
                    sendWithoutResponse(
                            ControlMessage.of(
                                    MessageType.ALERT_ACK,
                                    alert.field(0),
                                    incidentVersion.incidentId(),
                                    Long.toString(incidentVersion.version()),
                                    current.name()
                            )
                    );
                } catch (IOException | ProtocolException exception) {
                    System.err.println(
                            "[CLIENT ERROR] Could not acknowledge alert "
                                    + incidentVersion.incidentId()
                                    + ": "
                                    + exception.getMessage()
                    );
                }
            }
        }

        if (receivedAlerts.add(incidentVersion)) {
            alertConsumer.accept(alert);
        }
    }

    private static void displayAlert(ControlMessage alert) {
        System.out.println("[ALERT] " + alert.fields());
    }

    private ClientProfile requireProfile() {
        ClientProfile current = profile;

        if (current == null) {
            throw new IllegalStateException(
                    "Client is not registered"
            );
        }

        return current;
    }

    private String nextRequestNumber() {
        return Long.toString(
                requestNumbers.getAndIncrement()
        );
    }

    private List<CampusLocation> parseLocations(
            String values
    ) {
        return Arrays.stream(values.split(","))
                .map(CampusLocation::valueOf)
                .toList();
    }

    private List<AlertCategory> parseCategories(
            String values
    ) {
        return Arrays.stream(values.split(","))
                .map(AlertCategory::valueOf)
                .toList();
    }

    @Override
    public void close() {
        running = false;
        socket.close();

        pendingResponses.values()
                .forEach(
                        response ->
                                response.completeExceptionally(
                                        new IOException(
                                                "Client stopped"
                                        )
                                )
                );

        pendingResponses.clear();
    }
}