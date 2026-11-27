package com.cenrs.client.control;

import com.cenrs.contract.ClientRole;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.UdpMessageCodec;
import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class ClientControllerAlertTest {
    private final UdpMessageCodec codec = new UdpMessageCodec();
    private final AtomicInteger displayedAlerts = new AtomicInteger();
    private final CountDownLatch duplicateDisplay =
            new CountDownLatch(1);

    @Test
    public void criticalDuplicateIsAcknowledgedAgainButProcessedOnce()
            throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (
                DatagramSocket fakeServer =
                        new DatagramSocket(0, loopback);
                ClientController client =
                        new ClientController(
                                new InetSocketAddress(
                                        loopback,
                                        fakeServer.getLocalPort()
                                ),
                                0,
                                6001,
                                loopback.getHostAddress(),
                                alert -> {
                                    if (
                                            displayedAlerts.incrementAndGet()
                                                    > 1
                                    ) {
                                        duplicateDisplay.countDown();
                                    }
                                }
                        )
        ) {
            fakeServer.setSoTimeout(2_000);
            ExecutorService executor =
                    Executors.newSingleThreadExecutor();
            try {
                Future<ControlMessage> registration =
                        executor.submit(
                                () -> client.register(
                                        "Alice",
                                        ClientRole.SUBSCRIBER
                                )
                        );

                DatagramPacket registerPacket = receivePacket(fakeServer);
                ControlMessage register = decode(registerPacket);
                send(
                        fakeServer,
                        registerPacket.getSocketAddress(),
                        ControlMessage.of(
                                MessageType.REGISTERED,
                                register.field(0),
                                "HALL,ALL",
                                "FIRE,ALL"
                        )
                );
                assertEquals(
                        MessageType.REGISTERED,
                        registration.get(2, TimeUnit.SECONDS).type()
                );

                ControlMessage critical = ControlMessage.of(
                        MessageType.ALERT,
                        "83",
                        "INC102",
                        "1",
                        "FIRE",
                        "HALL",
                        "CRITICAL",
                        "Fire Alarm",
                        "Evacuate",
                        "NONE"
                );
                sendAlert(fakeServer, client.localUdpPort(), critical);
                assertEquals(
                        ControlMessage.of(
                                MessageType.ALERT_ACK,
                                "83",
                                "INC102",
                                "1",
                                "Alice"
                        ),
                        decode(receivePacket(fakeServer))
                );
                awaitDisplayedAlerts(1);

                sendAlert(fakeServer, client.localUdpPort(), critical);
                assertEquals(
                        ControlMessage.of(
                                MessageType.ALERT_ACK,
                                "83",
                                "INC102",
                                "1",
                                "Alice"
                        ),
                        decode(receivePacket(fakeServer))
                );
                assertFalse(
                        duplicateDisplay.await(
                                100,
                                TimeUnit.MILLISECONDS
                        )
                );
                assertEquals(1, displayedAlerts.get());

                ControlMessage warning = ControlMessage.of(
                        MessageType.ALERT,
                        "84",
                        "INC102",
                        "2",
                        "FIRE",
                        "HALL",
                        "WARNING",
                        "Smoke",
                        "Stay clear",
                        "NONE"
                );
                sendAlert(fakeServer, client.localUdpPort(), warning);
                awaitDisplayedAlerts(2);

                fakeServer.setSoTimeout(150);
                try {
                    receivePacket(fakeServer);
                    throw new AssertionError(
                            "WARNING alert must not be acknowledged"
                    );
                } catch (SocketTimeoutException expected) {
                    assertEquals(2, displayedAlerts.get());
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private void awaitDisplayedAlerts(int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (
                displayedAlerts.get() != expected
                && System.nanoTime() < deadline
        ) {
            Thread.sleep(5);
        }

        assertEquals(expected, displayedAlerts.get());
    }

    private void sendAlert(
            DatagramSocket server,
            int clientPort,
            ControlMessage alert
    ) throws Exception {
        send(
                server,
                new InetSocketAddress("127.0.0.1", clientPort),
                alert
        );
    }

    private void send(
            DatagramSocket source,
            SocketAddress destination,
            ControlMessage message
    ) throws Exception {
        byte[] bytes = codec.encode(message);
        source.send(new DatagramPacket(
                bytes,
                bytes.length,
                destination
        ));
    }

    private DatagramPacket receivePacket(DatagramSocket socket) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[4097], 4097);
        socket.receive(packet);
        return packet;
    }

    private ControlMessage decode(DatagramPacket packet)throws Exception {

        byte[] bytes = Arrays.copyOf(packet.getData(),packet.getLength());
        return codec.decode(bytes);}
}
