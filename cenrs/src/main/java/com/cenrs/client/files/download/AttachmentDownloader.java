package com.cenrs.client.files.download;

import com.cenrs.client.files.AttachmentFiles;
import com.cenrs.client.files.AttachmentMessageIO;
import com.cenrs.client.files.Sha256;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

public final class AttachmentDownloader {
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000;
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 30_000;
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final String subscriberName;
    private final Path downloadDirectory;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int maxAttempts;
    private final AttachmentMessageIO messageIO =
        new AttachmentMessageIO();

    public AttachmentDownloader(
        String subscriberName,
        Path downloadDirectory
    ) {
        this(
            subscriberName,
            downloadDirectory,
            DEFAULT_CONNECT_TIMEOUT_MILLIS,
            DEFAULT_READ_TIMEOUT_MILLIS,
            DEFAULT_MAX_ATTEMPTS
        );
    }

    public AttachmentDownloader(
        String subscriberName,
        Path downloadDirectory,
        int connectTimeoutMillis,
        int readTimeoutMillis,
        int maxAttempts
    ) {
        if (subscriberName == null || subscriberName.isBlank()) {
            throw new IllegalArgumentException(
                "subscriber name cannot be blank"
            );
        }

        if (connectTimeoutMillis < 1 || readTimeoutMillis < 1) {
            throw new IllegalArgumentException(
                "TCP timeouts must be positive"
            );
        }

        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                "maximum attempts must be positive"
            );
        }

        this.subscriberName = subscriberName;
        this.downloadDirectory = Objects.requireNonNull(
            downloadDirectory
        );
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxAttempts = maxAttempts;
    }

    public Path download(AttachmentInfo info) throws IOException {
        Objects.requireNonNull(info);
        Files.createDirectories(downloadDirectory);
        Path destination = AttachmentFiles.resolve(
            downloadDirectory,
            info.fileName()
        );
        IOException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Path temporary = Files.createTempFile(
                downloadDirectory,
                "attachment-",
                ".part"
            );

            try {
                downloadOnce(info, temporary, destination);
                return destination;
            } catch (IOException | ProtocolException exception) {
                Files.deleteIfExists(temporary);
                lastFailure = new IOException(
                    "Attachment attempt "
                        + attempt
                        + " failed: "
                        + exception.getMessage(),
                    exception
                );
            }
        }

        throw new IOException(
            "Attachment download failed after "
                + maxAttempts
                + " attempts",
            lastFailure
        );
    }

    private void downloadOnce(
        AttachmentInfo info,
        Path temporary,
        Path destination
    ) throws IOException, ProtocolException {
        try (Socket socket = new Socket()) {
            socket.connect(
                new InetSocketAddress(
                    info.ipAddress(),
                    info.tcpPort()
                ),
                connectTimeoutMillis
            );
            socket.setSoTimeout(readTimeoutMillis);

            try {
                requestAttachment(socket.getOutputStream(), info);
                ControlMessage response = messageIO.read(
                    socket.getInputStream()
                );

                if (response.type() == MessageType.ATTACH_ERROR) {
                    validateCorrelation(response, info);
                    throw new PeerRejectedException(response.field(3));
                }

                DownloadHeader header = DownloadHeader.from(
                    response,
                    info
                );
                receiveExactly(
                    socket.getInputStream(),
                    temporary,
                    header.size()
                );

                String actualChecksum = Sha256.checksum(temporary);

                if (!actualChecksum.equals(header.checksum())) {
                    throw new IOException("Checksum mismatch");
                }

                moveCompletedFile(temporary, destination);
                sendOk(socket.getOutputStream(), info);
            } catch (PeerRejectedException exception) {
                throw exception;
            } catch (IOException | ProtocolException exception) {
                sendErrorIfPossible(
                    socket.getOutputStream(),
                    info,
                    safeReason(exception)
                );
                throw exception;
            }
        }
    }

    private void requestAttachment(
        OutputStream output,
        AttachmentInfo info
    ) throws IOException, ProtocolException {
        messageIO.write(
            output,
            ControlMessage.of(
                MessageType.GET_ATTACHMENT,
                info.requestNumber(),
                subscriberName,
                info.incidentId(),
                info.fileName()
            )
        );
    }

    private void receiveExactly(
        InputStream input,
        Path temporary,
        long size
    ) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = size;

        try (OutputStream file = Files.newOutputStream(temporary)) {
            while (remaining > 0) {
                int count = input.read(
                    buffer,
                    0,
                    (int) Math.min(buffer.length, remaining)
                );

                if (count == -1) {
                    throw new EOFException(
                        "Attachment ended before File_Size bytes"
                    );
                }

                file.write(buffer, 0, count);
                remaining -= count;
            }
        }
    }

    private void moveCompletedFile(
        Path temporary,
        Path destination
    ) throws IOException {
        try {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private void sendOk(
        OutputStream output,
        AttachmentInfo info
    ) throws IOException, ProtocolException {
        messageIO.write(
            output,
            ControlMessage.of(
                MessageType.ATTACH_OK,
                info.requestNumber(),
                info.incidentId(),
                info.fileName()
            )
        );
    }

    private void sendErrorIfPossible(
        OutputStream output,
        AttachmentInfo info,
        String reason
    ) {
        try {
            messageIO.write(
                output,
                ControlMessage.of(
                    MessageType.ATTACH_ERROR,
                    info.requestNumber(),
                    info.incidentId(),
                    info.fileName(),
                    reason
                )
            );
        } catch (IOException | ProtocolException exception) {
            // The connection may already be unavailable.
        }
    }

    private static void validateCorrelation(
        ControlMessage message,
        AttachmentInfo info
    ) throws ProtocolException {
        if (
            !message.field(0).equals(info.requestNumber())
            || !message.field(1).equals(info.incidentId())
            || !message.field(2).equals(info.fileName())
        ) {
            throw new ProtocolException(
                "Attachment response does not match its request"
            );
        }
    }

    private static String safeReason(Exception exception) {
        String reason = exception.getMessage();

        if (reason == null || reason.isBlank()) {
            return "Transfer failed";
        }

        reason = reason
            .replace('|', '/')
            .replace('\r', ' ')
            .replace('\n', ' ');

        return reason.length() <= 256
            ? reason
            : reason.substring(0, 256);
    }

    private record DownloadHeader(long size, String checksum) {
        private static DownloadHeader from(
            ControlMessage message,
            AttachmentInfo info
        ) throws ProtocolException {
            if (message.type() != MessageType.ATTACH_DATA) {
                throw new ProtocolException(
                    "Expected ATTACH_DATA"
                );
            }

            validateCorrelation(message, info);
            long size;

            try {
                size = Long.parseLong(message.field(3));

                if (size < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException exception) {
                throw new ProtocolException(
                    "Invalid attachment size",
                    exception
                );
            }

            String checksum = message.field(4);

            if (!checksum.matches("[0-9a-f]{64}")) {
                throw new ProtocolException(
                    "Invalid SHA-256 checksum"
                );
            }

            return new DownloadHeader(size, checksum);
        }
    }

    private static final class PeerRejectedException
        extends IOException {

        private PeerRejectedException(String reason) {
            super(reason);
        }
    }
}