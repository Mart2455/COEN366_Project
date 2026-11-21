package com.cenrs.client.files;

import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;
import com.cenrs.protocol.UdpMessageCodec;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Set;

public final class AttachmentMessageIO {
    private static final Set<MessageType> ATTACHMENT_TYPES = Set.of(
        MessageType.GET_ATTACHMENT,
        MessageType.ATTACH_DATA,
        MessageType.ATTACH_OK,
        MessageType.ATTACH_ERROR
    );

    private final UdpMessageCodec codec = new UdpMessageCodec();

    public void write(
        OutputStream output,
        ControlMessage message
    ) throws IOException, ProtocolException {
        Objects.requireNonNull(output);
        requireAttachmentType(message);

        output.write(codec.encode(message));
        output.write('\n');
        output.flush();
    }

    public ControlMessage read(InputStream input)
        throws IOException, ProtocolException {

        Objects.requireNonNull(input);
        ByteArrayOutputStream header = new ByteArrayOutputStream();

        while (true) {
            int next = input.read();
            if (next == -1) {
                throw new EOFException(
                    "Connection closed before the TCP header was complete"
                );
            }
            if (next == '\n') {
                break;
            }

            if (header.size() == UdpMessageCodec.MAX_DATAGRAM_BYTES) {
                throw new ProtocolException(
                    "TCP header exceeds "
                        + UdpMessageCodec.MAX_DATAGRAM_BYTES
                        + " bytes"
                );
            }

            header.write(next);
        }

        if (header.size() == 0) {
            throw new ProtocolException("TCP header cannot be empty");
        }

        ControlMessage message = codec.decode(header.toByteArray());
        requireAttachmentType(message);
        return message;
    }

    private void requireAttachmentType(ControlMessage message) {
        Objects.requireNonNull(message);

        if (!ATTACHMENT_TYPES.contains(message.type())) {
            throw new IllegalArgumentException(
                "Not an attachment transfer message: "
                    + message.type().wireName()
            );
        }
    }
}