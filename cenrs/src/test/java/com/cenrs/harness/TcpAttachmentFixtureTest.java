package com.cenrs.harness;

import static org.junit.Assert.assertArrayEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class TcpAttachmentFixtureTest {

    @Test
    public void declaresThreeBytesButSendsOnlyTwo() throws Exception {
        byte[] file = "abc".getBytes(StandardCharsets.UTF_8);

        //capture the bytes that would go to the socket
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TcpAttachmentFixture.writeTruncated(output, "94", "INC102", "map.txt", file);

        //known SHA-256 checksum of abc
        String checksum = "ba7816bf8f01cfea414140de5dae2223"
                        + "b00361a396177a9cb410ff61f20015ad";

        //expect the full size in the header and only ab in the body
        String expected = "ATTACH_DATA|94|INC102|map.txt|3|" + checksum + "\nab";
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), output.toByteArray());
    }

    @Test
    public void sendsFullLengthWithOneChangedByte() throws Exception {
        byte[] file = "abc".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        TcpAttachmentFixture.writeCorrupted(output, "94", "INC102", "map.txt", file);

        //the header should still contain the checksum of abc
        String checksum = "ba7816bf8f01cfea414140de5dae2223"
                        + "b00361a396177a9cb410ff61f20015ad";

        //the body should contain all three bytes with the last one changed
        String expected = "ATTACH_DATA|94|INC102|map.txt|3|" + checksum + "\nabb";
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), output.toByteArray());
    }

}