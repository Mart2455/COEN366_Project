package com.cenrs.server.sync;

import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;
import com.cenrs.protocol.TcpMessageIO;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP listener of the Emergency Server for GET_ACTIVE_ALERTS (spec 2.10).
 *
 * One request per connection:
 *   client connects, sends GET_ACTIVE_ALERTS, server answers, server closes.
 * Each connection runs on its own (virtual) thread, so a slow client never
 * blocks other clients or the UDP processing.
 */
public final class SyncTcpServer implements AutoCloseable {

    /** protocol.md: default TCP read timeout. */
    public static final int READ_TIMEOUT_MILLIS = 30_000;

    private final ServerSocket serverSocket;
    private final ActiveAlertSyncService service;
    private final TcpMessageIO io = new TcpMessageIO();

    private final ExecutorService connections =
            Executors.newVirtualThreadPerTaskExecutor();

    private final AtomicBoolean running = new AtomicBoolean();
    private final int readTimeoutMillis;
    private Thread acceptThread;

    public SyncTcpServer(
            int tcpPort,
            ActiveAlertSyncService service
    ) throws IOException {
        this(tcpPort, service, READ_TIMEOUT_MILLIS);
    }

    public SyncTcpServer(
            int tcpPort,
            ActiveAlertSyncService service,
            int readTimeoutMillis
    ) throws IOException {
        this.service = Objects.requireNonNull(service);
        this.readTimeoutMillis = readTimeoutMillis;

        serverSocket = new ServerSocket();
        // Lets the server restart right away on the same port.
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(tcpPort));
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /** Starts accepting connections in the background and returns immediately. */
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        acceptThread = Thread.ofPlatform()
                .name("cenrs-sync-accept")
                .start(this::acceptLoop);

        System.out.println(
                "CENRS sync listening on TCP port " + port()
        );
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();

                // Hand the connection to its own thread and go back to accept().
                connections.submit(() -> serve(client));
            } catch (SocketException exception) {
                if (!running.get()) {
                    break;  // close() was called
                }

                System.err.println("[SYNC ERROR] " + exception.getMessage());
            } catch (IOException exception) {
                System.err.println("[SYNC ERROR] " + exception.getMessage());
            }
        }
    }

    /** Handles one connection: read one request, send the answer, close. */
    private void serve(Socket client) {
        String peer = String.valueOf(client.getRemoteSocketAddress());

        try (client) {
            client.setSoTimeout(readTimeoutMillis);

            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();

            byte[] line = io.readLine(input);
            List<ControlMessage> response = answer(line, peer);

            for (ControlMessage message : response) {
                io.write(output, message);
            }

            System.out.println(
                    "[TCP SENT] " + peer + " "
                            + response.get(0).type().wireName()
                            + " " + response.get(0).fields()
                            + (response.size() > 1
                                    ? " + " + (response.size() - 1) + " record(s)"
                                    : "")
            );
        } catch (ProtocolException exception) {
            // The request number could not be read, so no answer can be matched.
            System.err.println(
                    "[SYNC DISCARDED] " + peer + " " + exception.getMessage()
            );
        } catch (IOException exception) {
            System.err.println(
                    "[SYNC ERROR] " + peer + " " + exception.getMessage()
            );
        }
    }

    private List<ControlMessage> answer(byte[] line, String peer)
            throws ProtocolException {

        ControlMessage request;

        try {
            request = io.decode(line);
        } catch (ProtocolException exception) {
            // Malformed, but if RQ# is readable we can still deny it properly.
            String requestNumber = readableRequestNumber(line);

            if (requestNumber == null) {
                throw exception;
            }

            System.err.println(
                    "[SYNC MALFORMED] " + peer + " " + exception.getMessage()
            );

            return List.of(
                    ControlMessage.of(
                            MessageType.ACTIVE_ALERTS_DENIED,
                            requestNumber,
                            "Malformed request"
                    )
            );
        }

        System.out.println(
                "[TCP RECEIVED] " + peer + " "
                        + request.type().wireName() + " " + request.fields()
        );

        if (request.type() != MessageType.GET_ACTIVE_ALERTS) {
            String requestNumber = readableRequestNumber(line);

            if (requestNumber == null) {
                throw new ProtocolException(
                        "Unsupported TCP request: " + request.type().wireName()
                );
            }

            return List.of(
                    ControlMessage.of(
                            MessageType.ACTIVE_ALERTS_DENIED,
                            requestNumber,
                            "Unsupported request"
                    )
            );
        }

        return service.handle(request);
    }

    /** "NAME|RQ#|..." -> RQ#, or null if it is not a positive number. */
    private static String readableRequestNumber(byte[] line) {
        String[] parts = new String(line, StandardCharsets.UTF_8).split("\\|", -1);

        if (parts.length < 2) {
            return null;
        }

        try {
            return Long.parseLong(parts[1]) > 0 ? parts[1] : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @Override
    public void close() {
        running.set(false);

        try {
            serverSocket.close();  // unblocks accept()
        } catch (IOException ignored) {
            // already closed
        }

        connections.shutdown();

        if (acceptThread != null) {
            try {
                acceptThread.join(1_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
