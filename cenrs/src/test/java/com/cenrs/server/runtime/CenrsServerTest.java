package com.cenrs.server.runtime;

import com.cenrs.server.delivery.AcknowledgementScheduler;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.TcpMessageIO;
import com.cenrs.protocol.UdpMessageCodec;

import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Issue 19: the whole server, over real UDP and TCP sockets. */
public class CenrsServerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final UdpMessageCodec codec = new UdpMessageCodec();
    private final TcpMessageIO tcp = new TcpMessageIO();
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @After
    public void tearDown() throws Exception {
        for (AutoCloseable closeable : toClose) {
            closeable.close();
        }
    }

    @Test
    public void fullFlowSurvivesACrashAndRestart() throws Exception {
        Path stateFile = folder.getRoot().toPath().resolve("cenrs-state.txt");

        // ---- first run
        CenrsServer server = start(stateFile);
        DatagramSocket alice = client();
        DatagramSocket security = client();

        assertEquals(
                MessageType.REGISTERED,
                send(server, alice, register("1", "Alice", "SUBSCRIBER", alice)).type()
        );
        assertEquals(
                ControlMessage.of(MessageType.SUBSCRIBED, "2"),
                send(server, alice, "SUBSCRIBE|2|HALL|FIRE")
        );
        assertEquals(
                MessageType.REGISTERED,
                send(server, security, register("1", "Security", "PUBLISHER", security)).type()
        );
        assertEquals(
                ControlMessage.of(MessageType.ALERT_ACCEPTED, "3", "INC100"),
                send(server, security,
                        "PUBLISH_ALERT|3|FIRE|HALL|CRITICAL|Fire Alarm|Evacuate Building|NONE")
        );
        ControlMessage aliceAlert = receive(alice);
        assertEquals(MessageType.ALERT, aliceAlert.type());
        assertEquals("INC100", aliceAlert.field(1));

        assertEquals(
                ControlMessage.of(MessageType.STATUS_RECORDED, "4"),
                send(server, alice, "STATUS_REPORT|4|INC100|Alice|SAFE")
        );

        // TCP sync sees the new incident.
        List<ControlMessage> active = sync(server, "GET_ACTIVE_ALERTS|5|Alice", 2);
        assertEquals(ControlMessage.of(MessageType.ACTIVE_ALERTS, "5", "1"), active.get(0));
        assertEquals("INC100", active.get(1).field(0));

        // Malformed but RQ# readable: the request type's own denial comes back.
        assertEquals(
                ControlMessage.of(MessageType.ALERT_DENIED, "6", "Malformed request"),
                send(server, security, "PUBLISH_ALERT|6|FIRE")
        );

        server.close();  // "crash"

        // ---- second run with the same snapshot file
        CenrsServer restarted = start(stateFile);

        // Alice is known but inactive: she must REGISTER again first.
        assertEquals(
                ControlMessage.of(
                        MessageType.SUBSCRIPTION_DENIED, "7", "Client not registered"
                ),
                send(restarted, alice, "SUBSCRIBE|7|ALL|ALL")
        );
        assertEquals(
                MessageType.REGISTERED,
                send(restarted, alice, register("8", "Alice", "SUBSCRIBER", alice)).type()
        );

        // Her subscription and the incident were recovered.
        List<ControlMessage> recovered = sync(restarted, "GET_ACTIVE_ALERTS|9|Alice", 2);
        assertEquals("INC100", recovered.get(1).field(0));

        // Incident IDs are not reused after the restart.
        send(restarted, security, register("10", "Security", "PUBLISHER", security));
        assertEquals(
                ControlMessage.of(MessageType.ALERT_ACCEPTED, "11", "INC101"),
                send(restarted, security,
                        "PUBLISH_ALERT|11|FIRE|HALL|WARNING|Smoke|Stay Away|NONE")
        );
    }

    @Test
    public void manyClientsAreServedConcurrently() throws Exception {
        CenrsServer server = start(folder.getRoot().toPath().resolve("state.txt"));
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<ControlMessage>> answers = new ArrayList<>();

        for (int i = 1; i <= 20; i++) {
            String name = "Client" + i;
            Callable<ControlMessage> call = () -> {
                DatagramSocket socket = client();
                return send(server, socket, register("1", name, "SUBSCRIBER", socket));
            };
            answers.add(pool.submit(call));
        }

        for (Future<ControlMessage> answer : answers) {
            assertEquals(MessageType.REGISTERED, answer.get(10, TimeUnit.SECONDS).type());
        }

        pool.shutdown();
    }

    @Test
    public void publishedIncidentIsDistributedToMatchingSubscribers()
            throws Exception {
        Path stateFile = folder.getRoot().toPath().resolve("distribution-state.txt");
        CenrsServer server = start(stateFile);
        DatagramSocket alice = client();
        DatagramSocket bob = client();
        DatagramSocket security = client();

        send(server, alice, register("1", "Alice", "SUBSCRIBER", alice));
        send(server, alice, "SUBSCRIBE|2|HALL|FIRE");
        send(server, bob, register("1", "Bob", "BOTH", bob));
        send(server, bob, "SUBSCRIBE|2|HALL|ALL");
        send(server, security, register("1", "Security", "PUBLISHER", security));

        ControlMessage accepted = send(
                server,
                security,
                "PUBLISH_ALERT|3|FIRE|HALL|CRITICAL|Fire Alarm|Evacuate|floor plan.pdf"
        );
        assertEquals(
                ControlMessage.of(
                        MessageType.ALERT_ACCEPTED,
                        "3",
                        "INC100"
                ),
                accepted
        );

        ControlMessage aliceAlert = receive(alice);
        ControlMessage bobAlert = receive(bob);

        assertEquals(MessageType.ALERT, aliceAlert.type());
        assertEquals(MessageType.ALERT, bobAlert.type());
        assertTrue(Long.parseLong(aliceAlert.field(0)) > 0);
        assertTrue(Long.parseLong(bobAlert.field(0)) > 0);
        assertTrue(!aliceAlert.field(0).equals(bobAlert.field(0)));

        for (ControlMessage alert : List.of(aliceAlert, bobAlert)) {
            assertEquals("INC100", alert.field(1));
            assertEquals("1", alert.field(2));
            assertEquals("FIRE", alert.field(3));
            assertEquals("HALL", alert.field(4));
            assertEquals("CRITICAL", alert.field(5));
            assertEquals("Fire Alarm", alert.field(6));
            assertEquals("Evacuate", alert.field(7));
            assertEquals("floor plan.pdf", alert.field(8));
        }

        assertEquals(
                ControlMessage.of(
                        MessageType.ATTACH_INFO,
                        "4",
                        "INC100",
                        "Security",
                        "127.0.0.1",
                        Integer.toString(security.getLocalPort() + 1),
                        "floor plan.pdf"
                ),
                send(
                        server,
                        alice,
                        "ATTACH_REQ|4|INC100|floor plan.pdf"
                )
        );
    }

    @Test
    public void criticalAlertRetriesUntilAcknowledgedByRegisteredEndpoint()
            throws Exception {
        CenrsServer server = start(
                folder.getRoot().toPath().resolve("ack-state.txt"),
                Duration.ofMillis(400)
        );
        DatagramSocket alice = client();
        DatagramSocket publisher = client();
        DatagramSocket impostor = client();

        send(server, alice, register("1", "Alice", "SUBSCRIBER", alice));
        send(server, alice, "SUBSCRIBE|2|HALL|FIRE");
        send(server, publisher, register("1", "Security", "PUBLISHER", publisher));
        assertEquals(
                MessageType.ALERT_ACCEPTED,
                send(
                        server,
                        publisher,
                        "PUBLISH_ALERT|3|FIRE|HALL|CRITICAL|Fire|Evacuate|NONE"
                ).type()
        );

        ControlMessage first = receive(alice);
        assertEquals(MessageType.ALERT, first.type());

        sendWithoutResponse(
                server,
                impostor,
                "ALERT_ACK|"
                        + first.field(0)
                        + "|"
                        + first.field(1)
                        + "|"
                        + first.field(2)
                        + "|Alice"
        );

        assertEquals(
                MessageType.STATUS_RECORDED,
                send(
                        server,
                        alice,
                        "STATUS_REPORT|4|INC100|Alice|SAFE"
                ).type()
        );

        ControlMessage retry = receive(alice);
        assertEquals(first, retry);

        sendWithoutResponse(
                server,
                alice,
                "ALERT_ACK|"
                        + first.field(0)
                        + "|"
                        + first.field(1)
                        + "|"
                        + first.field(2)
                        + "|Alice"
        );

        alice.setSoTimeout(700);
        try {
            receive(alice);
            throw new AssertionError("Acknowledged alert was retransmitted");
        } catch (SocketTimeoutException expected) {
            // The matching ACK cancelled the delivery timer.
        }
    }

    // ------------------------------------------------------------- helpers

    private CenrsServer start(Path stateFile) throws Exception {
        return start(
                stateFile,
                AcknowledgementScheduler.DEFAULT_TIMEOUT
        );
    }

    private CenrsServer start(Path stateFile, Duration acknowledgementTimeout)
            throws Exception {
        CenrsServer server = new CenrsServer(
                0,
                0,
                stateFile,
                new LoggingIncidentEventSink(),
                acknowledgementTimeout
        );
        toClose.add(server);

        Thread runner = new Thread(() -> {
            try {
                server.run();
            } catch (Exception exception) {
                System.err.println(exception.getMessage());
            }
        });
        runner.start();

        // Wait until the TCP sync listener accepts connections.
        for (int i = 0; i < 100; i++) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", server.tcpPort()), 200);
                break;
            } catch (Exception notYet) {
                Thread.sleep(20);
            }
        }
        return server;
    }

    private DatagramSocket client() throws Exception {
        DatagramSocket socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        socket.setSoTimeout(3_000);
        synchronized (toClose) {
            toClose.add(socket);
        }
        return socket;
    }

    private static String register(String rq, String name, String role, DatagramSocket socket) {
        return "REGISTER|" + rq + "|" + name + "|" + role + "|127.0.0.1|"
                + socket.getLocalPort() + "|" + (socket.getLocalPort() + 1);
    }

    /** Sends one UDP request and waits for one UDP reply. */
    private ControlMessage send(CenrsServer server, DatagramSocket socket, String message)
            throws Exception {

        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(
                bytes, bytes.length,
                new InetSocketAddress("127.0.0.1", server.udpPort())
        ));

        DatagramPacket reply = new DatagramPacket(new byte[4097], 4097);
        socket.receive(reply);

        return codec.decode(Arrays.copyOf(reply.getData(), reply.getLength()));
    }

    private ControlMessage receive(DatagramSocket socket) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[4097], 4097);
        socket.receive(packet);
        return codec.decode(Arrays.copyOf(packet.getData(), packet.getLength()));
    }

    private void sendWithoutResponse(
            CenrsServer server,
            DatagramSocket socket,
            String message
    ) throws Exception {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(
                bytes,
                bytes.length,
                new InetSocketAddress("127.0.0.1", server.udpPort())
        ));
    }

    /** Opens a TCP connection, sends one request, reads the expected lines. */
    private List<ControlMessage> sync(CenrsServer server, String request, int lines)
            throws Exception {

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", server.tcpPort()), 2_000);
            socket.setSoTimeout(3_000);

            OutputStream output = socket.getOutputStream();
            output.write((request + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            List<ControlMessage> answer = new ArrayList<>();
            for (int i = 0; i < lines; i++) {
                answer.add(tcp.read(socket.getInputStream()));
            }
            return answer;
        }
    }
}
