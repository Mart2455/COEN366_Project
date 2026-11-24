package com.cenrs.protocol;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Reads and writes control messages on a TCP connection.
 *
 * TCP is a byte stream with no message boundaries, so each message
 * ends with a newline (see protocol.md). A line can be at most 4096 bytes.
 */
public final class TcpMessageIO {

    private final UdpMessageCodec codec = new UdpMessageCodec();

    public void write(
            OutputStream output,
            ControlMessage message
    ) throws IOException, ProtocolException {

        Objects.requireNonNull(output, "output cannot be null");

        output.write(codec.encode(message));
        output.write('\n');
        output.flush();
    }

    /** Reads one line and decodes it. Throws EOFException if the peer closed first. */
    public ControlMessage read(InputStream input)
            throws IOException, ProtocolException {

        return codec.decode(readLine(input));
    }

    /** Decodes a line that was already read with readLine(). */
    public ControlMessage decode(byte[] line)
            throws ProtocolException {

        return codec.decode(line);
    }

    /** Reads raw bytes up to (not including) the newline. */
    public byte[] readLine(InputStream input)
            throws IOException, ProtocolException {

        Objects.requireNonNull(input, "input cannot be null");

        ByteArrayOutputStream line = new ByteArrayOutputStream();

        while (true) {
            int next = input.read();

            if (next == -1) {
                throw new EOFException(
                        "Connection closed before the message was complete"
                );
            }

            if (next == '\n') {
                break;
            }

            if (line.size() == UdpMessageCodec.MAX_DATAGRAM_BYTES) {
                throw new ProtocolException(
                        "TCP message exceeds "
                                + UdpMessageCodec.MAX_DATAGRAM_BYTES
                                + " bytes"
                );
            }

            line.write(next);
        }

        if (line.size() == 0) {
            throw new ProtocolException("TCP message cannot be empty");
        }

        return line.toByteArray();
    }
}
