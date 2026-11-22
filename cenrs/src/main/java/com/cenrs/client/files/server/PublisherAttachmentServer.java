package com.cenrs.client.files.server;

import com.cenrs.client.files.AttachmentFiles;
import com.cenrs.client.files.AttachmentMessageIO;
import com.cenrs.client.files.Sha256;
import com.cenrs.contract.port.FileCatalogPort;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PublisherAttachmentServer implements AutoCloseable {
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 30_000;

    private final String publisherName;
    private final int configuredPort;
    private final FileCatalogPort catalog;
    private final int readTimeoutMillis;
    private final AttachmentMessageIO messageIO =
        new AttachmentMessageIO();
    private final ExecutorService workers;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    public PublisherAttachmentServer(
        String publisherName,
        int port,
        FileCatalogPort catalog
    ) {
        this(
            publisherName,
            port,
            catalog,
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            DEFAULT_READ_TIMEOUT_MILLIS
        );
    }

    public PublisherAttachmentServer(
        String publisherName,
        int port,
        FileCatalogPort catalog,
        int workerCount,
        int readTimeoutMillis
    ) {
        this.publisherName = requireText(
            publisherName,
            "publisher name"
        );

        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("invalid TCP port");
        }

        if (workerCount < 1) {
            throw new IllegalArgumentException(
                "worker count must be positive"
            );
        }

        if (readTimeoutMillis < 1) {
            throw new IllegalArgumentException(
                "read timeout must be positive"
            );
        }

        configuredPort = port;
        this.catalog = Objects.requireNonNull(catalog);
        this.readTimeoutMillis = readTimeoutMillis;
        workers = Executors.newFixedThreadPool(workerCount);
    }

    public synchronized void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException(
                "attachment server is already running"
            );
        }

        ServerSocket socket = new ServerSocket();

        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(configuredPort));
            serverSocket = socket;

            acceptThread = new Thread(
                this::acceptConnections,
                "attachment-accept-" + publisherName
            );
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException exception) {
            running.set(false);
            socket.close();
            throw exception;
        }
    }

    public int localPort() {
        ServerSocket socket = serverSocket;

        if (socket == null || !socket.isBound()) {
            throw new IllegalStateException(
                "attachment server has not started"
            );
        }

        return socket.getLocalPort();
    }

    private void acceptConnections() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                client.setSoTimeout(readTimeoutMillis);
                workers.submit(() -> handle(client));
            } catch (SocketException exception) {
                if (running.get()) {
                    close();
                }
            } catch (IOException exception) {
                if (running.get()) {
                    close();
                }
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            ControlMessage message = messageIO.read(
                client.getInputStream()
            );

            if (message.type() != MessageType.GET_ATTACHMENT) {
                return;
            }

            AttachmentRequest request;

            try {
                request = AttachmentRequest.from(message);
            } catch (IllegalArgumentException exception) {
                sendError(
                    client.getOutputStream(),
                    message.field(0),
                    message.field(2),
                    message.field(3),
                    exception.getMessage()
                );
                return;
            }

            Optional<Path> located = catalog.find(
                publisherName,
                request.incidentId(),
                request.fileName()
            );

            if (located.isEmpty()) {
                sendError(
                    client.getOutputStream(),
                    request.requestNumber(),
                    request.incidentId(),
                    request.fileName(),
                    "Attachment unavailable"
                );
                return;
            }

            sendFile(client, request, located.get());
        } catch (IOException | ProtocolException exception) {
            // One failed peer must not stop the attachment server.
        }
    }

    private void sendFile(
        Socket client,
        AttachmentRequest request,
        Path file
    ) throws IOException, ProtocolException {
        long size = Files.size(file);
        String checksum = Sha256.checksum(file);
        OutputStream output = client.getOutputStream();

        messageIO.write(
            output,
            ControlMessage.of(
                MessageType.ATTACH_DATA,
                request.requestNumber(),
                request.incidentId(),
                request.fileName(),
                Long.toString(size),
                checksum
            )
        );

        writeExactly(file, size, output);
        ControlMessage result = messageIO.read(client.getInputStream());
        validateResult(result, request);
    }

    private void writeExactly(
        Path file,
        long size,
        OutputStream output
    ) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = size;

        try (InputStream input = Files.newInputStream(file)) {
            while (remaining > 0) {
                int count = input.read(
                    buffer,
                    0,
                    (int) Math.min(buffer.length, remaining)
                );

                if (count == -1) {
                    throw new EOFException(
                        "Attachment changed during transfer"
                    );
                }

                output.write(buffer, 0, count);
                remaining -= count;
            }
        }

        output.flush();
    }

    private void validateResult(
        ControlMessage result,
        AttachmentRequest request
    ) throws ProtocolException {
        if (
            result.type() != MessageType.ATTACH_OK
            && result.type() != MessageType.ATTACH_ERROR
        ) {
            throw new ProtocolException(
                "Expected ATTACH_OK or ATTACH_ERROR"
            );
        }

        if (
            !result.field(0).equals(request.requestNumber())
            || !result.field(1).equals(request.incidentId())
            || !result.field(2).equals(request.fileName())
        ) {
            throw new ProtocolException(
                "Attachment result does not match its request"
            );
        }
    }

    private void sendError(
        OutputStream output,
        String requestNumber,
        String incidentId,
        String fileName,
        String reason
    ) throws IOException, ProtocolException {
        messageIO.write(
            output,
            ControlMessage.of(
                MessageType.ATTACH_ERROR,
                requestNumber,
                incidentId,
                fileName,
                reason
            )
        );
    }

    @Override
    public synchronized void close() {
        if (!running.getAndSet(false)) {
            return;
        }

        ServerSocket socket = serverSocket;

        if (socket != null) {
            try {
                socket.close();
            } catch (IOException exception) {
                // The server is already shutting down.
            }
        }

        workers.shutdownNow();

        try {
            workers.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record AttachmentRequest(
        String requestNumber,
        String subscriberName,
        String incidentId,
        String fileName
    ) {
        private static AttachmentRequest from(ControlMessage message) {
            String requestNumber = message.field(0);

            try {
                if (Long.parseLong(requestNumber) < 1) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                    "Invalid request number"
                );
            }

            return new AttachmentRequest(
                requestNumber,
                requireText(message.field(1), "subscriber name"),
                requireText(message.field(2), "incident ID"),
                AttachmentFiles.requireSafeFileName(message.field(3))
            );
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                label + " cannot be blank"
            );
        }

        return value;
    }
}