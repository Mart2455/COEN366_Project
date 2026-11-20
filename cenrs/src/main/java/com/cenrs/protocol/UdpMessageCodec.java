package com.cenrs.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

public final class UdpMessageCodec {
    public static final int MAX_DATAGRAM_BYTES = 4096;

    public byte[] encode(ControlMessage message)
        throws ProtocolException {

        Objects.requireNonNull(
            message,
            "message cannot be null"
        );

        StringJoiner encoded =
            new StringJoiner("|");

        encoded.add(message.type().wireName());

        for (String field : message.fields()) {
            validateField(field);
            encoded.add(field);
        }

        byte[] bytes = encoded
            .toString()
            .getBytes(StandardCharsets.UTF_8);

        validateSize(bytes.length);

        return bytes;
    }

    public ControlMessage decode(byte[] datagram)
        throws ProtocolException 
        {

        if (datagram == null || datagram.length == 0) {
            throw new ProtocolException(
                "UDP datagram cannot be empty"
            );
        }

        validateSize(datagram.length);

        String encoded = decodeUtf8(datagram);

        if (
            encoded.contains("\r")
            || encoded.contains("\n")
        ) {
            throw new ProtocolException(
                "UDP message cannot contain a line break"
            );
        }

        String[] parts = encoded.split("\\|", -1);
        MessageType type =
            MessageType.fromWireName(parts[0]);

        int actualFieldCount = parts.length - 1;

        if (actualFieldCount != type.fieldCount()) {
            throw new ProtocolException(
                type.wireName()
                    + " requires "
                    + type.fieldCount()
                    + " fields but received "
                    + actualFieldCount
            );
        }

        List<String> fields = List.copyOf(
            Arrays.asList(parts)
                .subList(1, parts.length)
        );

        for (String field : fields) {
            validateField(field);
        }

        return new ControlMessage(type, fields);
    }

    private String decodeUtf8(byte[] datagram)
        throws ProtocolException {

        try {
            return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(
                    CodingErrorAction.REPORT
                )
                .onUnmappableCharacter(
                    CodingErrorAction.REPORT
                )
                .decode(ByteBuffer.wrap(datagram))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new ProtocolException( "Message is not valid UTF-8", exception );
        }
    }

    private void validateField(String field)
        throws ProtocolException {

        if (field == null || field.isEmpty()) {
            throw new ProtocolException(
                "Message fields cannot be empty"
            );
        }

        if (
            field.contains("|")
            || field.contains("\r")
            || field.contains("\n")
        ) {
            throw new ProtocolException(
                "Message field contains a reserved character"
            );
        }
    }

    private void validateSize(int size)
        throws ProtocolException {

        if (size > MAX_DATAGRAM_BYTES) {
            throw new ProtocolException(
                "UDP message exceeds " + MAX_DATAGRAM_BYTES + " bytes" );
        }
    }
}