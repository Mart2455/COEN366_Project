package com.cenrs.client.files.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.cenrs.client.files.AttachmentMessageIO;
import com.cenrs.client.files.Sha256;
import com.cenrs.client.files.server.LocalFileCatalog;
import com.cenrs.client.files.server.PublisherAttachmentServer;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;

public class AttachmentDownloaderTest {
    private final AttachmentMessageIO messageIO =
        new AttachmentMessageIO();

    @Test
    public void downloadsFromPublisherAfterChecksumPasses()
        throws Exception {

        byte[] expected = "campus evacuation map".getBytes();
        Path source = Files.createTempFile("source", ".pdf");
        Files.write(source, expected);
        LocalFileCatalog catalog = new LocalFileCatalog();
        catalog.add("CampusSecurity", "INC102", "map.pdf", source);
        Path downloads = Files.createTempDirectory("downloads");

        try (
            PublisherAttachmentServer server =
                new PublisherAttachmentServer(
                    "CampusSecurity",
                    0,
                    catalog
                )
        ) {
            server.start();
            AttachmentDownloader downloader =
                new AttachmentDownloader("Alice", downloads);

            Path downloaded = downloader.download(
                info(server.localPort(), "94")
            );

            assertEquals(
                downloads.resolve("map.pdf").toAbsolutePath().normalize(),
                downloaded
            );
            assertArrayEquals(expected, Files.readAllBytes(downloaded));
            assertFalse(hasTemporaryFile(downloads));
        }
    }

    @Test
    public void checksumMismatchIsRejectedAndCleanedUp()
        throws Exception {

        byte[] correct = "correct".getBytes();
        byte[] corrupt = "corrupt".getBytes();
        Path checksumSource = Files.createTempFile("correct", ".bin");
        Files.write(checksumSource, correct);
        Path downloads = Files.createTempDirectory("downloads");

        try (ServerSocket peer = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();

            try {
                Future<ControlMessage> result = executor.submit(() -> {
                    try (Socket socket = peer.accept()) {
                        messageIO.read(socket.getInputStream());
                        messageIO.write(
                            socket.getOutputStream(),
                            ControlMessage.of(
                                MessageType.ATTACH_DATA,
                                "95",
                                "INC102",
                                "map.pdf",
                                Long.toString(corrupt.length),
                                Sha256.checksum(checksumSource)
                            )
                        );
                        socket.getOutputStream().write(corrupt);
                        socket.getOutputStream().flush();
                        return messageIO.read(socket.getInputStream());
                    }
                });

                AttachmentDownloader downloader =
                    oneAttemptDownloader(downloads);

                expectDownloadFailure(
                    downloader,
                    info(peer.getLocalPort(), "95")
                );

                assertEquals(
                    MessageType.ATTACH_ERROR,
                    result.get().type()
                );
                assertFalse(Files.exists(downloads.resolve("map.pdf")));
                assertFalse(hasTemporaryFile(downloads));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void truncatedTransferIsRejectedAndCleanedUp()
        throws Exception {

        Path downloads = Files.createTempDirectory("downloads");

        try (ServerSocket peer = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();

            try {
                Future<?> transfer = executor.submit(() -> {
                    try (Socket socket = peer.accept()) {
                        messageIO.read(socket.getInputStream());
                        messageIO.write(
                            socket.getOutputStream(),
                            ControlMessage.of(
                                MessageType.ATTACH_DATA,
                                "96",
                                "INC102",
                                "map.pdf",
                                "20",
                                "0".repeat(64)
                            )
                        );
                        socket.getOutputStream().write(
                            "short".getBytes()
                        );
                    }

                    return null;
                });

                expectDownloadFailure(
                    oneAttemptDownloader(downloads),
                    info(peer.getLocalPort(), "96")
                );
                transfer.get();

                assertFalse(Files.exists(downloads.resolve("map.pdf")));
                assertFalse(hasTemporaryFile(downloads));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void retriesWithNewConnectionThenSucceeds()
        throws Exception {

        byte[] expected = "valid attachment".getBytes();
        Path checksumSource = Files.createTempFile("valid", ".bin");
        Files.write(checksumSource, expected);
        String checksum = Sha256.checksum(checksumSource);
        Path downloads = Files.createTempDirectory("downloads");

        try (ServerSocket peer = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();

            try {
                Future<Integer> attempts = executor.submit(() -> {
                    int accepted = 0;

                    while (accepted < 2) {
                        try (Socket socket = peer.accept()) {
                            accepted++;
                            messageIO.read(socket.getInputStream());
                            messageIO.write(
                                socket.getOutputStream(),
                                ControlMessage.of(
                                    MessageType.ATTACH_DATA,
                                    "97",
                                    "INC102",
                                    "map.pdf",
                                    Long.toString(expected.length),
                                    checksum
                                )
                            );

                            byte[] bytes = accepted == 1
                                ? "invalid payload!".getBytes()
                                : expected;

                            socket.getOutputStream().write(bytes);
                            socket.getOutputStream().flush();
                            messageIO.read(socket.getInputStream());
                        }
                    }

                    return accepted;
                });

                AttachmentDownloader downloader =
                    new AttachmentDownloader(
                        "Alice",
                        downloads,
                        2_000,
                        2_000,
                        2
                    );

                Path downloaded = downloader.download(
                    info(peer.getLocalPort(), "97")
                );

                assertArrayEquals(
                    expected,
                    Files.readAllBytes(downloaded)
                );
                assertEquals(Integer.valueOf(2), attempts.get());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void unsafeDestinationNameIsRejected() {
        try {
            new AttachmentInfo(
                "98",
                "INC102",
                "CampusSecurity",
                "127.0.0.1",
                6100,
                "../secret.txt"
            );
            fail("Expected unsafe file name to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("path"));
        }
    }

    private AttachmentDownloader oneAttemptDownloader(Path directory) {
        return new AttachmentDownloader(
            "Alice",
            directory,
            2_000,
            2_000,
            1
        );
    }

    private AttachmentInfo info(int port, String requestNumber) {
        return new AttachmentInfo(
            requestNumber,
            "INC102",
            "CampusSecurity",
            "127.0.0.1",
            port,
            "map.pdf"
        );
    }

    private void expectDownloadFailure(
        AttachmentDownloader downloader,
        AttachmentInfo info
    ) throws Exception {
        try {
            downloader.download(info);
            fail("Expected attachment download to fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("failed"));
        }
    }

    private boolean hasTemporaryFile(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.anyMatch(
                path -> path.getFileName().toString().endsWith(".part")
            );
        }
    }
}