package com.cenrs.client.files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.cenrs.client.files.server.LocalFileCatalog;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class AttachmentFoundationTest {
    @Test
    public void attachmentHeaderRoundTrips() throws Exception {
        AttachmentMessageIO messageIO = new AttachmentMessageIO();
        ControlMessage request = ControlMessage.of(
            MessageType.GET_ATTACHMENT,
            "94",
            "Alice",
            "INC102",
            "evacuation map.pdf"
        );
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        messageIO.write(output, request);

        byte[] encoded = output.toByteArray();
        assertEquals('\n', encoded[encoded.length - 1]);
        assertEquals(
            request,
            messageIO.read(new ByteArrayInputStream(encoded))
        );
    }

    @Test(expected = EOFException.class)
    public void incompleteHeaderIsRejected() throws Exception {
        new AttachmentMessageIO().read(
            new ByteArrayInputStream(
                "GET_ATTACHMENT|94|Alice|INC102|map.pdf"
                    .getBytes(StandardCharsets.UTF_8)
            )
        );
    }

    @Test
    public void fileNameCannotEscapeDirectory() throws Exception {
        Path directory = Files.createTempDirectory("attachments");

        try {
            AttachmentFiles.resolve(directory, "../secret.txt");
            fail("Expected unsafe file name to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("path"));
        }
    }

    @Test
    public void sha256UsesCompleteFileContents() throws Exception {
        Path file = Files.createTempFile("checksum", ".txt");
        Files.writeString(file, "hello");

        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e"
                + "1b161e5c1fa7425e73043362938b9824",
            Sha256.checksum(file)
        );
    }

    @Test
    public void catalogFindsAndRemovesIncidentFile() throws Exception {
        LocalFileCatalog catalog = new LocalFileCatalog();
        Path file = Files.createTempFile("attachment", ".pdf");
        Files.write(file, new byte[] { 1, 2, 3 });

        catalog.add("CampusSecurity", "INC102", "map.pdf", file);

        assertTrue(
            catalog.find(
                "CampusSecurity",
                "INC102",
                "map.pdf"
            ).isPresent()
        );

        catalog.removeIncident("INC102");

        assertFalse(
            catalog.find(
                "CampusSecurity",
                "INC102",
                "map.pdf"
            ).isPresent()
        );
    }

    @Test
    public void catalogRejectsTraversalLookup() {
        LocalFileCatalog catalog = new LocalFileCatalog();

        assertFalse(
            catalog.find(
                "CampusSecurity",
                "INC102",
                "../../secret.txt"
            ).isPresent()
        );
    }
}