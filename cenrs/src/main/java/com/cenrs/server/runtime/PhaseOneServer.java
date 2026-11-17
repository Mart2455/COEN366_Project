package com.cenrs.server.runtime;

import com.cenrs.network.UdpTransport;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;
import com.cenrs.protocol.UdpMessageCodec;
import com.cenrs.server.persistence.FilePersistence;
import com.cenrs.server.persistence.ServerStateStore;
import com.cenrs.server.registration.RegistrationService;
import com.cenrs.server.state.ServerState;
import com.cenrs.server.subscription.SubscriptionService;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PhaseOneServer
        implements AutoCloseable {

    private final DatagramSocket socket;
    private final UdpMessageCodec codec;
    private final RegistrationService registration;
    private final SubscriptionService subscription;
    private final ServerStateStore stateStore;

    private final ExecutorService workers =
            Executors.newVirtualThreadPerTaskExecutor();

    private final AtomicBoolean running =
            new AtomicBoolean();

    private final Object stateChangeLock =
            new Object();

    public PhaseOneServer(
            int udpPort,
            Path stateFile
    ) throws SocketException {

        socket = new DatagramSocket(udpPort);
        codec = new UdpMessageCodec();

        ServerState state = new ServerState();
        UdpTransport transport =
                new UdpTransport(socket);

        stateStore = new ServerStateStore(
                state,
                state,
                new FilePersistence(stateFile)
        );

        registration = new RegistrationService(
                state,
                state,
                transport,
                codec
        );

        subscription = new SubscriptionService(
                state,
                state,
                transport,
                codec
        );
    }

    public int port() {
        return socket.getLocalPort();
    }

    public void run() throws IOException {
        stateStore.restore();
        running.set(true);

        System.out.println(
                "CENRS server listening on UDP port "
                        + port()
        );

        while (running.get()) {
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
            } catch (SocketException exception) {
                if (!running.get()) {
                    break;
                }

                throw exception;
            }

            byte[] datagram = Arrays.copyOfRange(
                    packet.getData(),
                    packet.getOffset(),
                    packet.getOffset()
                            + packet.getLength()
            );

            InetSocketAddress sender =
                    new InetSocketAddress(
                            packet.getAddress(),
                            packet.getPort()
                    );

            // Receiving continues while each request runs independently.
            workers.submit(
                    () -> process(datagram, sender)
            );
        }
    }

    private void process(
            byte[] datagram,
            InetSocketAddress sender
    ) {
        try {
            ControlMessage request =
                    codec.decode(datagram);

            System.out.println(
                    "[UDP RECEIVED] "
                            + sender
                            + " "
                            + request.type().wireName()
                            + " "
                            + request.fields()
            );

            // State changes and their saved snapshot stay consistent.
            synchronized (stateChangeLock) {
                dispatch(request, sender);
                stateStore.save();
            }
        } catch (
                IOException
                | ProtocolException
                | IllegalArgumentException exception
        ) {
            System.err.println(
                    "[SERVER ERROR] "
                            + sender
                            + " "
                            + exception.getMessage()
            );
        }
    }

    private void dispatch(
            ControlMessage request,
            InetSocketAddress sender
    ) throws IOException, ProtocolException {

        MessageType type = request.type();

        switch (type) {
            case REGISTER, DE_REGISTER ->
                    registration.handle(request, sender);

            case SUBSCRIBE, UNSUBSCRIBE ->
                    subscription.handle(request, sender);

            default -> System.err.println(
                    "[SERVER IGNORED] Unsupported Phase One message: "
                            + type.wireName()
            );
        }
    }

    @Override
    public void close() {
        running.set(false);
        socket.close();
        workers.shutdown();

        System.out.println("CENRS server stopped");
    }
}