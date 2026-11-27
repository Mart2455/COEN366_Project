package com.cenrs.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Issue 9: one shared framing for every TCP control message (sync and attachments). */
public class TcpMessageIOTest {

    private final TcpMessageIO io = new TcpMessageIO();

    @Test
    public void messageIsWrittenAsOneLineEndingWithNewline() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        io.write(output, ControlMessage.of(MessageType.GET_ACTIVE_ALERTS, "22", "Alice"));

        assertEquals(
                "GET_ACTIVE_ALERTS|22|Alice\n",
                output.toString(StandardCharsets.UTF_8)
        );
    }

    @Test
    public void severalMessagesInOneStreamAreSeparatedByNewlines() throws Exception {
        // TCP can deliver two messages glued together: the newline separates them.
        InputStream input = stream("ACTIVE_ALERTS|22|1\nACTIVE_ALERT|INC102|2|FIRE|HALL|WARNING|ACTIVE|Fire|Leave now|NONE\n");

        assertEquals(MessageType.ACTIVE_ALERTS, io.read(input).type());
        assertEquals("INC102", io.read(input).field(0));
    }

    @Test
    public void bytesAfterTheHeaderStayInTheStream() throws Exception {
        // ATTACH_DATA: the header line is followed by raw file bytes.
        InputStream input = stream("ATTACH_DATA|94|INC102|map.pdf|3|abc\nXYZ");

        assertEquals(MessageType.ATTACH_DATA, io.read(input).type());
        assertArrayEquals("XYZ".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
    }

    @Test
    public void textWithSpacesAndAccentsRoundTrips() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ControlMessage message = ControlMessage.of(
                MessageType.ACTIVE_ALERT,
                "INC102", "2", "FIRE", "HALL", "WARNING", "ACTIVE",
                "Évacuation du café", "Restez à l'extérieur", "plan.pdf"
        );

        io.write(output, message);

        assertEquals(message, io.read(new ByteArrayInputStream(output.toByteArray())));
    }

    @Test(expected = EOFException.class)
    public void connectionClosedBeforeNewlineIsDetected() throws Exception {
        io.read(stream("GET_ACTIVE_ALERTS|22|Ali"));
    }

    @Test
    public void emptyLineIsRejected() throws Exception {
        try {
            io.read(stream("\n"));
            fail("Expected an empty message");
        } catch (ProtocolException expected) {
            assertTrue(expected.getMessage().contains("empty"));
        }
    }

    @Test
    public void lineLongerThan4096BytesIsRejected() throws Exception {
        String tooLong = "GET_ACTIVE_ALERTS|22|" + "a".repeat(5000) + "\n";

        try {
            io.read(stream(tooLong));
            fail("Expected an oversized message");
        } catch (ProtocolException expected) {
            assertTrue(expected.getMessage().contains("4096"));
        }
    }

    @Test
    public void malformedFieldCountIsRejected() throws Exception {
        try {
            io.read(stream("GET_ACTIVE_ALERTS|22\n"));
            fail("Expected a missing field");
        } catch (ProtocolException expected) {
            assertTrue(expected.getMessage().contains("requires 2 fields"));
        }
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
