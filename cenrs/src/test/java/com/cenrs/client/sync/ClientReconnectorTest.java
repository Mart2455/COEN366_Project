package com.cenrs.client.sync;

import com.cenrs.client.sync.ActiveAlertSyncClient.SyncDeniedException;
import com.cenrs.client.sync.ClientIncidentState.SyncReport;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.UdpMessageCodec;
import com.cenrs.server.runtime.CenrsServer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Issue 24: a client resumes against the real server after the server restarts. */
public class ClientReconnectorTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final UdpMessageCodec codec = new UdpMessageCodec();
    private final AtomicLong requestNumbers = new AtomicLong(1);
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @After
    public void tearDown() throws Exception {
        for (AutoCloseable closeable : toClose) {
            closeable.close();
        }
    }

    @Test
    public void clientSynchronizesThenResumesAfterServerRestart() throws Exception {
        Path stateFile = folder.getRoot().toPath().resolve("state.txt");
        CenrsServer server = start(stateFile);

        DatagramSocket alice = udpClient();
        DatagramSocket security = udpClient();
        send(server, alice, register("Alice", "SUBSCRIBER", alice));
        send(server, alice, "SUBSCRIBE|" + rq() + "|HALL|ALL");
        send(server, security, register("Security", "PUBLISHER", security));
        send(server, security, "PUBLISH_ALERT|" + rq() + "|FIRE|HALL|CRITICAL|Fire Alarm|Evacuate|NONE");

        // The client still remembers an old incident that was closed while it was away.
        ClientIncidentState state = new ClientIncidentState();
        state.apply(ClientIncidentStateTest.incident("INC050", 1));

        ClientReconnector reconnector = new ClientReconnector(
                "Alice",
                () -> send(server, alice, register("Alice", "SUBSCRIBER", alice)).type()
                        == MessageType.REGISTERED,
                new ActiveAlertSyncClient(
                        new InetSocketAddress("127.0.0.1", server.tcpPort()),
                        requestNumbers::getAndIncrement
                ),
                state
        );

        SyncReport first = reconnector.synchronize();
        assertEquals(List.of("INC100"), first.added());
        assertEquals(List.of("INC050"), first.noLongerActive());

        // ---- the server crashes and comes back on new ports with the same state file
        server.close();
        CenrsServer restarted = start(stateFile);

        ActiveAlertSyncClient syncAfterRestart = new ActiveAlertSyncClient(
                new InetSocketAddress("127.0.0.1", restarted.tcpPort()),
                requestNumbers::getAndIncrement
        );

        // Before registering again, the server refuses to synchronize.
        try {
            syncAfterRestart.fetchActive("Alice");
            fail("Expected a denial");
        } catch (SyncDeniedException denied) {
            assertEquals(ClientReconnector.NOT_REGISTERED_REASON, denied.reason());
        }

        // UDP requests are refused too, and the client can tell it must register again.
        ControlMessage denial = send(restarted, alice, "SUBSCRIBE|" + rq() + "|EV|FIRE");
        assertTrue(ClientReconnector.needsReRegistration(denial));

        // resume() = register again + synchronize.
        ClientReconnector afterRestart = new ClientReconnector(
                "Alice",
                () -> send(restarted, alice, register("Alice", "SUBSCRIBER", alice)).type()
                        == MessageType.REGISTERED,
                syncAfterRestart,
                state
        );

        SyncReport resumed = afterRestart.resume();
        assertEquals(List.of("INC100"), resumed.unchanged());
        assertEquals(1, state.activeIncidents().size());
    }

    @Test
    public void unreachableServerFailsAfterThreeAttempts() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }

        ActiveAlertSyncClient client = new ActiveAlertSyncClient(
                new InetSocketAddress("127.0.0.1", closedPort),
                requestNumbers::getAndIncrement,
                500,
                500
        );

        try {
            client.fetchActive("Alice");
            fail("Expected a connection failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("3 attempts"));
        }
    }

    @Test
    public void ordinaryDenialIsNotAReRegistrationSignal() {
        assertFalse(
                ClientReconnector.needsReRegistration(
                        ControlMessage.of(MessageType.SUBSCRIPTION_DENIED, "4", "Invalid category")
                )
        );
    }

    // ------------------------------------------------------------- helpers

    private String rq() {
        return Long.toString(requestNumbers.getAndIncrement());
    }

    private String register(String name, String role, DatagramSocket socket) {
        return "REGISTER|" + rq() + "|" + name + "|" + role + "|127.0.0.1|"
                + socket.getLocalPort() + "|" + (socket.getLocalPort() + 1);
    }

    private CenrsServer start(Path stateFile) throws Exception {
        CenrsServer server = new CenrsServer(0, 0, stateFile);
        toClose.add(server);

        Thread runner = new Thread(() -> {
            try {
                server.run();
            } catch (Exception exception) {
                System.err.println(exception.getMessage());
            }
        });
        runner.start();

        for (int i = 0; i < 100; i++) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", server.tcpPort()), 200);
                break;
            } catch (IOException notYet) {
                Thread.sleep(20);
            }
        }
        return server;
    }

    private DatagramSocket udpClient() throws Exception {
        DatagramSocket socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        socket.setSoTimeout(3_000);
        toClose.add(socket);
        return socket;
    }

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
}
