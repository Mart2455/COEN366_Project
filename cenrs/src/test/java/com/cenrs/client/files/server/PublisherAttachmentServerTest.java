package com.cenrs.client.files.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.cenrs.client.files.AttachmentMessageIO;
import com.cenrs.client.files.Sha256;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;

public class PublisherAttachmentServerTest {
    private final AttachmentMessageIO messageIO =
        new AttachmentMessageIO();

    @Test
    public void sendsHeaderAndExactFileBytes() throws Exception {
        byte[] expected = "evacuation map".getBytes();
        Path file = Files.createTempFile("attachment", ".pdf");
        Files.write(file, expected);
        LocalFileCatalog catalog = catalogWith(file);

        try (
            PublisherAttachmentServer server =
                new PublisherAttachmentServer(
                    "CampusSecurity",
                    0,
                    catalog
                )
        ) {
            server.start();

            try (Socket client = connect(server)) {
                messageIO.write(
                    client.getOutputStream(),
                    request("94", "map.pdf")
                );

                ControlMessage header = messageIO.read(
                    client.getInputStream()
                );

                assertEquals(MessageType.ATTACH_DATA, header.type());
                assertEquals("94", header.field(0));
                assertEquals("INC102", header.field(1));
                assertEquals("map.pdf", header.field(2));
                assertEquals(
                    Long.toString(expected.length),
                    header.field(3)
                );
                assertEquals(Sha256.checksum(file), header.field(4));

                assertArrayEquals(
                    expected,
                    client.getInputStream().readNBytes(expected.length)
                );

                messageIO.write(
                    client.getOutputStream(),
                    ControlMessage.of(
                        MessageType.ATTACH_OK,
                        "94",
                        "INC102",
                        "map.pdf"
                    )
                );

                assertEquals(-1, client.getInputStream().read());
            }
        }
    }

    @Test
    public void unavailableFileReturnsError() throws Exception {
        LocalFileCatalog catalog = new LocalFileCatalog();

        try (
            PublisherAttachmentServer server =
                new PublisherAttachmentServer(
                    "CampusSecurity",
                    0,
                    catalog
                )
        ) {
            server.start();

            try (Socket client = connect(server)) {
                messageIO.write(
                    client.getOutputStream(),
                    request("95", "missing.pdf")
                );

                ControlMessage response = messageIO.read(
                    client.getInputStream()
                );

                assertEquals(MessageType.ATTACH_ERROR, response.type());
                assertEquals("95", response.field(0));
                assertEquals("INC102", response.field(1));
                assertEquals("missing.pdf", response.field(2));
                assertEquals(
                    "Attachment unavailable",
                    response.field(3)
                );
            }
        }
    }

    @Test
    public void traversalRequestReturnsError() throws Exception {
        LocalFileCatalog catalog = new LocalFileCatalog();

        try (
            PublisherAttachmentServer server =
                new PublisherAttachmentServer(
                    "CampusSecurity",
                    0,
                    catalog
                )
        ) {
            server.start();

            try (Socket client = connect(server)) {
                messageIO.write(
                    client.getOutputStream(),
                    request("96", "../../secret.txt")
                );

                ControlMessage response = messageIO.read(
                    client.getInputStream()
                );

                assertEquals(MessageType.ATTACH_ERROR, response.type());
                assertTrue(response.field(3).contains("path"));
            }
        }
    }

    @Test
    public void servesTwoSubscribersConcurrently() throws Exception {
        byte[] expected = new byte[32_000];

        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) (index % 251);
        }

        Path file = Files.createTempFile("attachment", ".bin");
        Files.write(file, expected);
        LocalFileCatalog catalog = catalogWith(file);

        try (
            PublisherAttachmentServer server =
                new PublisherAttachmentServer(
                    "CampusSecurity",
                    0,
                    catalog,
                    2,
                    5_000
                )
        ) {
            server.start();
            ExecutorService clients = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            try {
                Future<byte[]> first = clients.submit(
                    () -> download(server, "101", ready, start)
                );
                Future<byte[]> second = clients.submit(
                    () -> download(server, "102", ready, start)
                );

                ready.await();
                start.countDown();

                assertArrayEquals(expected, first.get());
                assertArrayEquals(expected, second.get());
            } finally {
                clients.shutdownNow();
            }
        }
    }

    @Test
    public void disconnectedPeerDoesNotStopServer() throws Exception {
        byte[] expected = "still available".getBytes();
        Path file = Files.createTempFile("attachment", ".pdf");
        Files.write(file, expected);
        LocalFileCatalog catalog = catalogWith(file);

        try (
            PublisherAttachmentServer server =
                new PublisherAttachmentServer(
                    "CampusSecurity",
                    0,
                    catalog
                )
        ) {
            server.start();

            try (Socket disconnected = connect(server)) {
                messageIO.write(
                    disconnected.getOutputStream(),
                    request("110", "map.pdf")
                );
            }

            assertArrayEquals(
                expected,
                download(server, "111", null, null)
            );
        }
    }

    @Test
    public void subscriberErrorDoesNotStopServer() throws Exception {
        byte[] expected = "retry later".getBytes();
        Path file = Files.createTempFile("attachment", ".pdf");
        Files.write(file, expected);
        LocalFileCatalog catalog = catalogWith(file);

        try (
            PublisherAttachmentServer server =
                new PublisherAttachmentServer(
                    "CampusSecurity",
                    0,
                    catalog
                )
        ) {
            server.start();

            try (Socket client = connect(server)) {
                messageIO.write(
                    client.getOutputStream(),
                    request("120", "map.pdf")
                );

                ControlMessage header = messageIO.read(
                    client.getInputStream()
                );
                int size = Integer.parseInt(header.field(3));
                client.getInputStream().readNBytes(size);

                messageIO.write(
                    client.getOutputStream(),
                    ControlMessage.of(
                        MessageType.ATTACH_ERROR,
                        "120",
                        "INC102",
                        "map.pdf",
                        "Checksum mismatch"
                    )
                );
            }

            assertArrayEquals(
                expected,
                download(server, "121", null, null)
            );
        }
    }

    private LocalFileCatalog catalogWith(Path file) {
        LocalFileCatalog catalog = new LocalFileCatalog();
        catalog.add("CampusSecurity", "INC102", "map.pdf", file);
        return catalog;
    }

    private Socket connect(PublisherAttachmentServer server)
        throws Exception {

        Socket socket = new Socket(
            InetAddress.getLoopbackAddress(),
            server.localPort()
        );
        socket.setSoTimeout(5_000);
        return socket;
    }

    private ControlMessage request(String requestNumber, String fileName) {
        return ControlMessage.of(
            MessageType.GET_ATTACHMENT,
            requestNumber,
            "Alice",
            "INC102",
            fileName
        );
    }

    private byte[] download(
        PublisherAttachmentServer server,
        String requestNumber,
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        if (ready != null) {
            ready.countDown();
            start.await();
        }

        try (Socket client = connect(server)) {
            messageIO.write(
                client.getOutputStream(),
                request(requestNumber, "map.pdf")
            );

            InputStream input = client.getInputStream();
            ControlMessage header = messageIO.read(input);
            int size = Integer.parseInt(header.field(3));
            byte[] bytes = input.readNBytes(size);

            messageIO.write(
                client.getOutputStream(),
                ControlMessage.of(
                    MessageType.ATTACH_OK,
                    requestNumber,
                    "INC102",
                    "map.pdf"
                )
            );

            return bytes;
        }
    }
}