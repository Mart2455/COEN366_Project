package com.cenrs.protocol;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public class UdpMessageCodecTest {
    private final UdpMessageCodec codec =
        new UdpMessageCodec();

    @Test
    public void registrationRoundTrips()
        throws Exception {

        ControlMessage original = ControlMessage.of(
            MessageType.REGISTER,
            "1",
            "Alice",
            "SUBSCRIBER",
            "127.0.0.1",
            "5001",
            "6001"
        );

        byte[] encoded = codec.encode(original);
        ControlMessage decoded = codec.decode(encoded);

        assertEquals(original, decoded);
    }

    @Test
    public void fieldsMayContainSpaces()
        throws Exception {

        ControlMessage original = ControlMessage.of(
            MessageType.REGISTER_DENIED,
            "2",
            "Name already exists"
        );

        assertEquals(
            original,
            codec.decode(codec.encode(original))
        );
    }

    @Test(expected = ProtocolException.class)
    public void missingFieldsAreRejected()
        throws Exception {

        codec.decode(
            "REGISTER|1|Alice".getBytes(
                StandardCharsets.UTF_8
            )
        );
    }

    @Test(expected = ProtocolException.class)
    public void extraFieldsAreRejected()
        throws Exception {

        codec.decode(
            "SUBSCRIBED|1|EXTRA".getBytes(
                StandardCharsets.UTF_8
            )
        );
    }

    @Test(expected = ProtocolException.class)
    public void reservedCharactersAreRejected()
        throws Exception {

        codec.encode(
            ControlMessage.of(
                MessageType.REGISTER_DENIED,
                "1",
                "Invalid|name"
            )
        );
    }

    @Test(expected = ProtocolException.class)
    public void oversizedDatagramsAreRejected()
        throws Exception {

        byte[] oversized =
            new byte[UdpMessageCodec.MAX_DATAGRAM_BYTES + 1];

        Arrays.fill(oversized, (byte) 'A');

        codec.decode(oversized);
    }

    @Test(expected = ProtocolException.class)
    public void malformedUtf8IsRejected()
        throws Exception {

        codec.decode(
            new byte[] {
                (byte) 0xC3,
                (byte) 0x28
            }
        );
    }
}