package com.cenrs.server.runtime;

import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.port.IncidentEventSinkPort;
import com.cenrs.network.UdpTransport;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;
import com.cenrs.protocol.UdpMessageCodec;
import com.cenrs.server.attachment.AttachmentDiscoveryService;
import com.cenrs.server.delivery.AcknowledgementHandler;
import com.cenrs.server.delivery.AcknowledgementScheduler;
import com.cenrs.server.delivery.ExecutorDeliveryScheduler;
import com.cenrs.server.delivery.InMemoryDeliveryStateRepository;
import com.cenrs.server.incident.IncidentService;
import com.cenrs.server.incident.InMemoryIncidentStore;
import com.cenrs.server.incident.SequentialIncidentIdGenerator;
import com.cenrs.server.persistence.FilePersistence;
import com.cenrs.server.persistence.ServerStateStore;
import com.cenrs.server.registration.RegistrationService;
import com.cenrs.server.state.ServerState;
import com.cenrs.server.status.InMemoryStatusStore;
import com.cenrs.server.status.StatusService;
import com.cenrs.server.subscription.SubscriptionService;
import com.cenrs.server.sync.ActiveAlertSyncService;
import com.cenrs.server.sync.SyncTcpServer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The complete Emergency Server (issue 19).
 *
 * Threads:
 *   - UDP receiver: only receives datagrams and hands each one to a worker.
 *   - UDP workers: one virtual thread per request.
 *   - TCP sync listener (issue 17): one thread per GET_ACTIVE_ALERTS connection.
 *   - Acknowledgement timers: retransmit CRITICAL alerts without blocking anyone.
 *   - Snapshot saver: saves timer results (e.g. UNCONFIRMED deliveries) every few seconds.
 *
 * Every state change and its snapshot happen together under one lock (issue 18).
 */
public final class CenrsServer implements AutoCloseable {

    public static final int DEFAULT_UDP_PORT = 5000;
    public static final int DEFAULT_TCP_PORT = 6000;
    private static final long PERIODIC_SAVE_SECONDS = 5;

    private final DatagramSocket udpSocket;
    private final UdpTransport network;
    // Replies to a request are only sent after the change is saved.
    private final ReplyAfterSaveTransport transport;
    private final UdpMessageCodec codec = new UdpMessageCodec();

    // Shared state (issue 18)
    private final ServerState state = new ServerState();
    private final InMemoryIncidentStore incidents = new InMemoryIncidentStore();
    private final SequentialIncidentIdGenerator incidentIds =
            new SequentialIncidentIdGenerator();
    private final InMemoryStatusStore statuses = new InMemoryStatusStore();
    private final InMemoryDeliveryStateRepository deliveries =
            new InMemoryDeliveryStateRepository();
    private final ServerStateStore stateStore;

    // Services
    private final RegistrationService registration;
    private final SubscriptionService subscription;
    private final IncidentService incidentService;
    private final StatusService statusService;
    private final AttachmentDiscoveryService attachments;
    private final ExecutorDeliveryScheduler deliveryTimers;
    private final AcknowledgementScheduler acknowledgementScheduler;
    private final AcknowledgementHandler acknowledgements;
    private final SyncTcpServer syncServer;

    private final ExecutorService workers =
            Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService periodicSaver =
            Executors.newSingleThreadScheduledExecutor();

    // A ReentrantLock (not synchronized): virtual threads waiting for it give
    // their carrier thread back, so many concurrent requests cannot freeze the server.
    private final ReentrantLock stateChangeLock = new ReentrantLock();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public CenrsServer(int udpPort, int tcpPort, Path stateFile)
            throws IOException {
        this(udpPort, tcpPort, stateFile, new LoggingIncidentEventSink());
    }

    public CenrsServer(
            int udpPort,
            int tcpPort,
            Path stateFile,
            IncidentEventSinkPort eventSink
    ) throws IOException {

        udpSocket = new DatagramSocket(udpPort);
        network = new UdpTransport(udpSocket);
        transport = new ReplyAfterSaveTransport(network);

        stateStore = new ServerStateStore(
                state, state, incidents, incidentIds, statuses, deliveries,
                new FilePersistence(stateFile)
        );

        registration = new RegistrationService(state, state, transport, codec);
        subscription = new SubscriptionService(state, state, transport, codec);
        incidentService = new IncidentService(incidents, state, incidentIds, eventSink);
        statusService = new StatusService(state, incidents, statuses);
        attachments = new AttachmentDiscoveryService(incidents, state);

        deliveryTimers = new ExecutorDeliveryScheduler();
        acknowledgementScheduler = new AcknowledgementScheduler(
                Clock.systemUTC(), deliveryTimers, deliveries, transport
        );
        acknowledgements = new AcknowledgementHandler(acknowledgementScheduler);

        try {
            syncServer = new SyncTcpServer(
                    tcpPort,
                    new ActiveAlertSyncService(state, state, incidents)
            );
        } catch (IOException exception) {
            udpSocket.close();
            throw exception;
        }
    }

    public int udpPort() {
        return udpSocket.getLocalPort();
    }

    public int tcpPort() {
        return syncServer.port();
    }

    /** For issue 13: the routing sink needs these to send reliable alerts. */
    public AcknowledgementScheduler acknowledgementScheduler() {
        return acknowledgementScheduler;
    }

    /** Restores the snapshot, starts TCP sync, then receives UDP until close(). */
    public void run() throws IOException {
        stateStore.restore();
        running.set(true);

        syncServer.start();

        periodicSaver.scheduleWithFixedDelay(
                this::saveQuietly,
                PERIODIC_SAVE_SECONDS,
                PERIODIC_SAVE_SECONDS,
                TimeUnit.SECONDS
        );

        System.out.println("CENRS server listening on UDP port " + udpPort());

        while (running.get()) {
            byte[] buffer = new byte[UdpMessageCodec.MAX_DATAGRAM_BYTES + 1];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            try {
                udpSocket.receive(packet);
            } catch (SocketException exception) {
                if (!running.get()) {
                    break;  // close() was called
                }
                throw exception;
            }

            byte[] datagram = Arrays.copyOfRange(
                    packet.getData(),
                    packet.getOffset(),
                    packet.getOffset() + packet.getLength()
            );

            InetSocketAddress sender = new InetSocketAddress(
                    packet.getAddress(),
                    packet.getPort()
            );

            // The receiver never waits: each request runs on its own worker.
            workers.submit(() -> process(datagram, sender));
        }
    }

    private void process(byte[] datagram, InetSocketAddress sender) {
        ControlMessage request;

        try {
            request = codec.decode(datagram);
        } catch (ProtocolException exception) {
            rejectMalformed(datagram, sender, exception);
            return;
        }

        System.out.println(
                "[UDP RECEIVED] " + sender + " "
                        + request.type().wireName() + " " + request.fields()
        );

        try {
            // A state change and its saved snapshot stay consistent.
            stateChangeLock.lock();
            try {
                transport.begin();
                dispatch(request, sender);
                stateStore.save();
            } catch (IOException | ProtocolException | RuntimeException exception) {
                // Not saved: no reply goes out, so the client never gets a false "success".
                transport.discard();
                throw exception;
            } finally {
                stateChangeLock.unlock();
            }

            // Saved: now the replies can be sent.
            transport.commit();
        } catch (IOException | ProtocolException | RuntimeException exception) {
            // A bad request must never kill a server thread.
            System.err.println(
                    "[SERVER ERROR] " + sender + " " + exception.getMessage()
            );
        }
    }

    private void dispatch(ControlMessage request, InetSocketAddress sender)
            throws IOException, ProtocolException {

        MessageType type = request.type();

        // After a restart, a client must REGISTER again before anything else.
        if (type != MessageType.REGISTER && senderIsInactive(sender)) {
            Optional<ControlMessage> denial = DenialResponses.forRequest(
                    type, request.field(0), firstFieldAfterRequestNumber(request),
                    "Client not registered"
            );
            if (denial.isPresent()) {
                reply(sender, denial.get());
            }
            return;
        }

        switch (type) {
            case REGISTER, DE_REGISTER ->
                    registration.handle(request, sender);

            case SUBSCRIBE, UNSUBSCRIBE ->
                    subscription.handle(request, sender);

            case PUBLISH_ALERT ->
                    reply(sender, incidentService.publish(request, sender));

            case UPDATE_ALERT ->
                    reply(sender, incidentService.update(request, sender));

            case CLOSE_ALERT ->
                    reply(sender, incidentService.close(request, sender));

            case STATUS_REPORT ->
                    reply(sender, statusService.report(request, sender));

            case ATTACH_REQ ->
                    reply(sender, attachments.findAttachmentEndpoint(request));

            case ALERT_ACK -> {
                // No reply: a duplicate or late ACK is simply ignored.
                if (!acknowledgements.handle(request)) {
                    System.out.println(
                            "[ACK IGNORED] duplicate or unknown " + request.fields()
                    );
                }
            }

            default -> System.err.println(
                    "[SERVER IGNORED] Not a client-to-server UDP request: "
                            + type.wireName()
            );
        }
    }

    /** A client restored from the snapshot that has not registered again. */
    private boolean senderIsInactive(InetSocketAddress sender) {
        if (sender.getAddress() == null) {
            return false;
        }

        String ip = sender.getAddress().getHostAddress();

        return state.findAll()
                .stream()
                .filter(
                        client -> client.ipAddress().equals(ip)
                                && client.udpPort() == sender.getPort()
                )
                .map(ClientProfile::name)
                .anyMatch(name -> !state.isActive(name));
    }

    /** Malformed request: answer with that type's denial if RQ# is readable, else log it. */
    private void rejectMalformed(
            byte[] datagram,
            InetSocketAddress sender,
            ProtocolException problem
    ) {
        String text = new String(datagram, StandardCharsets.UTF_8);
        String[] parts = text.split("\\|", -1);

        System.err.println(
                "[UDP MALFORMED] " + sender + " " + problem.getMessage()
        );

        if (parts.length < 2 || !isPositiveNumber(parts[1])) {
            return;  // cannot correlate an answer
        }

        MessageType type;
        try {
            type = MessageType.fromWireName(parts[0]);
        } catch (ProtocolException unknownType) {
            return;
        }

        Optional<ControlMessage> denial = DenialResponses.forRequest(
                type, parts[1], parts.length > 2 ? parts[2] : null,
                "Malformed request"
        );

        if (denial.isPresent()) {
            try {
                reply(sender, denial.get());
            } catch (IOException | ProtocolException exception) {
                System.err.println("[SERVER ERROR] " + exception.getMessage());
            }
        }
    }

    private void reply(InetSocketAddress destination, ControlMessage message)
            throws IOException, ProtocolException {
        transport.send(destination, codec.encode(message));
    }

    private static String firstFieldAfterRequestNumber(ControlMessage request) {
        return request.fields().size() > 1 ? request.field(1) : null;
    }

    private static boolean isPositiveNumber(String value) {
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private void saveQuietly() {
        try {
            stateChangeLock.lock();
            try {
                stateStore.save();
            } finally {
                stateChangeLock.unlock();
            }
        } catch (IOException | RuntimeException exception) {
            System.err.println("[SAVE ERROR] " + exception.getMessage());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        running.set(false);
        udpSocket.close();  // unblocks receive()
        syncServer.close();
        periodicSaver.shutdownNow();
        acknowledgementScheduler.close();
        deliveryTimers.close();
        workers.shutdown();

        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }

        saveQuietly();  // last snapshot before exit
        System.out.println("CENRS server stopped");
    }
}
