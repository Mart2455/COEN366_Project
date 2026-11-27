package com.cenrs.client.sync;

import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;
import com.cenrs.protocol.TcpMessageIO;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Client side of GET_ACTIVE_ALERTS (spec 2.10).
 *
 * Opens a TCP connection to the Emergency Server, sends one request,
 * reads ACTIVE_ALERTS|RQ#|N then exactly N ACTIVE_ALERT lines.
 * A network failure is retried on a new connection, at most 3 attempts.
 */
public final class ActiveAlertSyncClient {

    /** protocol.md defaults */
    public static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    public static final int READ_TIMEOUT_MILLIS = 30_000;
    public static final int MAX_ATTEMPTS = 3;

    private final InetSocketAddress server;
    private final LongSupplier requestNumbers;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final TcpMessageIO io = new TcpMessageIO();

    public ActiveAlertSyncClient(
            InetSocketAddress serverTcpAddress,
            LongSupplier requestNumbers
    ) {
        this(serverTcpAddress, requestNumbers, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS);
    }

    public ActiveAlertSyncClient(
            InetSocketAddress serverTcpAddress,
            LongSupplier requestNumbers,
            int connectTimeoutMillis,
            int readTimeoutMillis
    ) {
        this.server = Objects.requireNonNull(serverTcpAddress);
        this.requestNumbers = Objects.requireNonNull(requestNumbers);
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /**
     * Returns the active incidents matching the client's subscriptions.
     *
     * @throws SyncDeniedException if the server answered ACTIVE_ALERTS_DENIED
     * @throws IOException         if all 3 attempts failed
     */
    public List<KnownIncident> fetchActive(String clientName)
            throws IOException, ProtocolException, SyncDeniedException {

        Objects.requireNonNull(clientName, "client name cannot be null");

        IOException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String requestNumber = Long.toString(requestNumbers.getAsLong());

            try {
                return fetchOnce(requestNumber, clientName);
            } catch (IOException exception) {
                lastFailure = exception;
                System.err.println(
                        "[SYNC FAILED] attempt " + attempt + "/" + MAX_ATTEMPTS
                                + ": " + exception.getMessage()
                );
            }
        }

        throw new IOException(
                "Synchronization failed after " + MAX_ATTEMPTS + " attempts",
                lastFailure
        );
    }

    private List<KnownIncident> fetchOnce(String requestNumber, String clientName)
            throws IOException, ProtocolException, SyncDeniedException {

        try (Socket socket = new Socket()) {
            socket.connect(server, connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);

            ControlMessage request = ControlMessage.of(
                    MessageType.GET_ACTIVE_ALERTS, requestNumber, clientName
            );
            io.write(socket.getOutputStream(), request);
            System.out.println("[TCP SENT] " + server + " " + request.fields());

            InputStream input = socket.getInputStream();
            ControlMessage header = io.read(input);

            if (!header.field(0).equals(requestNumber)) {
                throw new ProtocolException(
                        "Response RQ# " + header.field(0)
                                + " does not match request " + requestNumber
                );
            }

            if (header.type() == MessageType.ACTIVE_ALERTS_DENIED) {
                throw new SyncDeniedException(header.field(1));
            }

            if (header.type() != MessageType.ACTIVE_ALERTS) {
                throw new ProtocolException(
                        "Unexpected sync response " + header.type().wireName()
                );
            }

            int count = parseCount(header.field(1));
            List<KnownIncident> incidents = new ArrayList<>(count);

            // Read exactly Number_Alerts records: that is the message boundary.
            for (int i = 0; i < count; i++) {
                incidents.add(KnownIncident.fromActiveAlert(io.read(input)));
            }

            System.out.println(
                    "[TCP RECEIVED] " + server + " ACTIVE_ALERTS " + count + " record(s)"
            );
            return incidents;
        }
    }

    private static int parseCount(String value) throws ProtocolException {
        try {
            int count = Integer.parseInt(value);

            if (count < 0) {
                throw new NumberFormatException("negative");
            }
            return count;
        } catch (NumberFormatException exception) {
            throw new ProtocolException("Invalid Number_Alerts: " + value);
        }
    }

    /** The server refused the synchronization (e.g. "Client not registered"). */
    public static final class SyncDeniedException extends Exception {
        private final String reason;

        public SyncDeniedException(String reason) {
            super("Synchronization denied: " + reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
