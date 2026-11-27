package com.cenrs.client.files;

import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;
import com.cenrs.protocol.TcpMessageIO;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Set;

/**
 * Peer-to-peer attachment headers over TCP.
 * Framing (one message per line, max 4096 bytes) comes from the shared TcpMessageIO;
 * this class only makes sure the message is an attachment message.
 */
public final class AttachmentMessageIO {
    private static final Set<MessageType> ATTACHMENT_TYPES = Set.of(
        MessageType.GET_ATTACHMENT,
        MessageType.ATTACH_DATA,
        MessageType.ATTACH_OK,
        MessageType.ATTACH_ERROR
    );

    private final TcpMessageIO framing = new TcpMessageIO();

    public void write(
        OutputStream output,
        ControlMessage message
    ) throws IOException, ProtocolException {
        Objects.requireNonNull(output);
        requireAttachmentType(message);

        framing.write(output, message);
    }

    public ControlMessage read(InputStream input)
        throws IOException, ProtocolException {
        Objects.requireNonNull(input);

        ControlMessage message = framing.read(input);
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
