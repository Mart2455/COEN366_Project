package com.cenrs.server.sync;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.Subscription;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.TcpMessageIO;
import com.cenrs.server.incident.InMemoryIncidentStore;
import com.cenrs.server.state.ServerState;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Real TCP connections to the sync listener (issue 17). */
public class SyncTcpServerTest {

    private final TcpMessageIO io = new TcpMessageIO();
    private SyncTcpServer server;

    @Before
    public void setUp() throws Exception {
        ServerState state = new ServerState();
        InMemoryIncidentStore incidents = new InMemoryIncidentStore();

        state.addIfAbsent(new ClientProfile("Alice", ClientRole.SUBSCRIBER, "127.0.0.1", 5001, 6001));
        state.add("Alice", new Subscription(CampusLocation.HALL, AlertCategory.FIRE));

        incidents.create(
                ActiveAlertSyncServiceTest.incident(
                        "INC102", AlertCategory.FIRE, CampusLocation.HALL, IncidentStatus.ACTIVE
                )
        );

        // Port 0 = let the OS pick a free port. Short read timeout for the slow-client test.
        server = new SyncTcpServer(0, new ActiveAlertSyncService(state, state, incidents), 2_000);
        server.start();
    }

    @After
    public void tearDown() {
        server.close();
    }

    @Test
    public void clientReceivesHeaderThenRecordsOverTcp() throws Exception {
        List<ControlMessage> response = sync("GET_ACTIVE_ALERTS|22|Alice", 2);

        assertEquals(ControlMessage.of(MessageType.ACTIVE_ALERTS, "22", "1"), response.get(0));
        assertEquals("INC102", response.get(1).field(0));
        assertEquals("ACTIVE", response.get(1).field(5));
    }

    @Test
    public void severalClientsAreServedAtTheSameTime() throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(10);
        List<Future<List<ControlMessage>>> results = new ArrayList<>();

        for (int i = 1; i <= 10; i++) {
            String rq = Integer.toString(i);
            Callable<List<ControlMessage>> call =
                    () -> sync("GET_ACTIVE_ALERTS|" + rq + "|Alice", 2);
            results.add(clients.submit(call));
        }

        for (int i = 1; i <= 10; i++) {
            List<ControlMessage> response = results.get(i - 1).get(5, TimeUnit.SECONDS);
            assertEquals(Integer.toString(i), response.get(0).field(0));
            assertEquals("1", response.get(0).field(1));
        }

        clients.shutdown();
    }

    @Test
    public void slowClientDoesNotBlockOthers() throws Exception {
        // This client connects but never sends anything.
        try (Socket idle = new Socket()) {
            idle.connect(new InetSocketAddress("127.0.0.1", server.port()));

            // Another client is still answered right away.
            List<ControlMessage> response = sync("GET_ACTIVE_ALERTS|3|Alice", 2);
            assertEquals("1", response.get(0).field(1));
        }
    }

    @Test
    public void malformedRequestWithReadableNumberIsDenied() throws Exception {
        // Missing the Name field.
        List<ControlMessage> response = sync("GET_ACTIVE_ALERTS|31", 1);

        assertEquals(
                ControlMessage.of(MessageType.ACTIVE_ALERTS_DENIED, "31", "Malformed request"),
                response.get(0)
        );
    }

    @Test
    public void unknownClientIsDeniedOverTcp() throws Exception {
        List<ControlMessage> response = sync("GET_ACTIVE_ALERTS|40|Mallory", 1);

        assertEquals(MessageType.ACTIVE_ALERTS_DENIED, response.get(0).type());
    }

    /** Opens a connection, sends one line, reads the expected number of lines. */
    private List<ControlMessage> sync(String request, int expectedLines) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 2_000);
            socket.setSoTimeout(5_000);

            OutputStream output = socket.getOutputStream();
            output.write((request + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = socket.getInputStream();
            List<ControlMessage> lines = new ArrayList<>();

            for (int i = 0; i < expectedLines; i++) {
                lines.add(io.read(input));
            }

            // The server closes the connection after its answer.
            assertEquals(-1, input.read());
            return lines;
        }
    }
}
