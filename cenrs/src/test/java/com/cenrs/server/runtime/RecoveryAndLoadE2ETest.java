package com.cenrs.server.runtime;

import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.TcpMessageIO;
import com.cenrs.protocol.UdpMessageCodec;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Issue 29: crash recovery and concurrency under load, end to end.
 *
 *  1. A real crash: the server runs in its own JVM and is killed with SIGKILL
 *     (no shutdown hook, no final save). A new server must recover everything.
 *  2. Load: many clients register, publish, report and synchronize at the same time.
 *     Nothing may be lost, duplicated or corrupted, and it must survive a restart.
 */
public class RecoveryAndLoadE2ETest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final UdpMessageCodec codec = new UdpMessageCodec();
    private final TcpMessageIO tcp = new TcpMessageIO();
    private final AtomicLong requestNumbers = new AtomicLong(1);
    private final List<AutoCloseable> toClose = new ArrayList<>();
    private Process serverProcess;

    @After
    public void tearDown() throws Exception {
        if (serverProcess != null) {
            serverProcess.destroyForcibly();
        }
        synchronized (toClose) {
            for (AutoCloseable closeable : toClose) {
                closeable.close();
            }
        }
    }

    // ------------------------------------------------------------- 1. crash

    @Test
    public void serverKilledWithSigkillRecoversEverything() throws Exception {
        Path stateFile = folder.getRoot().toPath().resolve("data").resolve("cenrs-state.txt");
        int udpPort = freeUdpPort();
        int tcpPort = freeTcpPort();

        // The real server, in a separate JVM, exactly as it runs at the demo.
        serverProcess = new ProcessBuilder(
                javaExecutable(), "-cp", System.getProperty("java.class.path"),
                "com.cenrs.EmergencyServer",
                Integer.toString(udpPort), Integer.toString(tcpPort), stateFile.toString()
        )
                .redirectErrorStream(true)
                .redirectOutput(folder.newFile("server.log"))
                .start();
        waitForTcp(tcpPort);

        InetSocketAddress udp = new InetSocketAddress("127.0.0.1", udpPort);
        DatagramSocket alice = udpClient();
        DatagramSocket security = udpClient();

        assertEquals(MessageType.REGISTERED, send(udp, alice, register("Alice", "SUBSCRIBER", alice)).type());
        assertEquals(MessageType.SUBSCRIBED, send(udp, alice, "SUBSCRIBE|" + rq() + "|ALL|FIRE").type());
        assertEquals(MessageType.REGISTERED, send(udp, security, register("Security", "PUBLISHER", security)).type());

        for (int i = 0; i < 3; i++) {
            assertEquals(
                    MessageType.ALERT_ACCEPTED,
                    send(udp, security, "PUBLISH_ALERT|" + rq() + "|FIRE|HALL|CRITICAL|Fire " + i + "|Evacuate|NONE").type()
            );
        }
        assertEquals(MessageType.STATUS_RECORDED, send(udp, alice, "STATUS_REPORT|" + rq() + "|INC101|Alice|NEED_HELP").type());

        // ---- CRASH: SIGKILL, no shutdown hook, no final save.
        serverProcess.destroyForcibly();
        assertTrue(serverProcess.waitFor(5, TimeUnit.SECONDS));

        // A crash in the middle of a later save would leave a half-written temporary file.
        Files.writeString(stateFile.resolveSibling("cenrs-state.txt123456.tmp"), "CENRS-STATE|2\nINCIDENT|INC1");

        // ---- RESTART from the same snapshot
        CenrsServer restarted = startInProcess(stateFile);
        InetSocketAddress newUdp = new InetSocketAddress("127.0.0.1", restarted.udpPort());

        // Every response was sent after its save, so all 3 incidents survived.
        assertEquals(MessageType.REGISTERED, send(newUdp, alice, register("Alice", "SUBSCRIBER", alice)).type());
        List<ControlMessage> active = sync(restarted.tcpPort(), "Alice", 4);
        assertEquals("3", active.get(0).field(1));
        assertEquals(List.of("INC100", "INC101", "INC102"),
                active.subList(1, 4).stream().map(record -> record.field(0)).toList());

        // The status report survived too.
        String snapshot = Files.readString(stateFile);
        assertTrue(snapshot.contains("STATUS|INC101|Alice|NEED_HELP"));

        // IDs continue where they stopped: no reuse after the crash.
        send(newUdp, security, register("Security", "PUBLISHER", security));
        String publishRq = rq();
        assertEquals(
                ControlMessage.of(MessageType.ALERT_ACCEPTED, publishRq, "INC103"),
                send(newUdp, security, "PUBLISH_ALERT|" + publishRq + "|FIRE|EV|WARNING|Smoke|Stay out|NONE")
        );
    }

    // -------------------------------------------------------------- 2. load

    @Test
    public void concurrentLoadLosesNothingAndSurvivesRestart() throws Exception {
        final int subscribers = 30;
        final int publishers = 5;
        final int alertsPerPublisher = 4;
        final int totalAlerts = publishers * alertsPerPublisher;

        Path stateFile = folder.getRoot().toPath().resolve("load-state.txt");
        CenrsServer server = startInProcess(stateFile);
        InetSocketAddress udp = new InetSocketAddress("127.0.0.1", server.udpPort());
        ExecutorService pool = Executors.newFixedThreadPool(subscribers + publishers);

        // ---- phase 1: everyone registers and subscribes at the same time
        List<DatagramSocket> subscriberSockets = new ArrayList<>();
        List<DatagramSocket> publisherSockets = new ArrayList<>();
        for (int i = 0; i < subscribers; i++) subscriberSockets.add(udpClient());
        for (int i = 0; i < publishers; i++) publisherSockets.add(udpClient());

        List<Callable<Void>> setup = new ArrayList<>();
        for (int i = 0; i < subscribers; i++) {
            DatagramSocket socket = subscriberSockets.get(i);
            String name = "Sub" + i;
            setup.add(() -> {
                assertEquals(MessageType.REGISTERED, send(udp, socket, register(name, "SUBSCRIBER", socket)).type());
                assertEquals(MessageType.SUBSCRIBED, send(udp, socket, "SUBSCRIBE|" + rq() + "|HALL|ALL").type());
                return null;
            });
        }
        for (int i = 0; i < publishers; i++) {
            DatagramSocket socket = publisherSockets.get(i);
            String name = "Pub" + i;
            setup.add(() -> {
                assertEquals(MessageType.REGISTERED, send(udp, socket, register(name, "PUBLISHER", socket)).type());
                return null;
            });
        }
        runAll(pool, setup);

        // ---- phase 2: publishers publish while subscribers synchronize over TCP
        Set<String> incidentIds = ConcurrentHashMap.newKeySet();
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<Void>> load = new ArrayList<>();

        for (DatagramSocket socket : publisherSockets) {
            load.add(() -> {
                go.await();
                for (int n = 0; n < alertsPerPublisher; n++) {
                    ControlMessage accepted = send(udp, socket,
                            "PUBLISH_ALERT|" + rq() + "|FIRE|HALL|WARNING|Alarm|Leave|NONE");
                    assertEquals(MessageType.ALERT_ACCEPTED, accepted.type());
                    // add() is false for a duplicate ID
                    assertTrue("duplicate ID " + accepted.field(1), incidentIds.add(accepted.field(1)));
                }
                return null;
            });
        }
        for (int i = 0; i < subscribers; i++) {
            String name = "Sub" + i;
            load.add(() -> {
                go.await();
                // Whatever moment it runs, a sync must be a consistent list.
                List<ControlMessage> header = sync(server.tcpPort(), name, 1);
                assertEquals(MessageType.ACTIVE_ALERTS, header.get(0).type());
                return null;
            });
        }

        long start = System.nanoTime();
        List<Future<Void>> running = new ArrayList<>();
        for (Callable<Void> task : load) running.add(pool.submit(task));
        go.countDown();
        for (Future<Void> task : running) task.get(30, TimeUnit.SECONDS);
        long loadMillis = (System.nanoTime() - start) / 1_000_000;

        // IDs are unique and contiguous: INC100 .. INC119
        Set<String> expectedIds = new HashSet<>();
        for (int n = 0; n < totalAlerts; n++) expectedIds.add("INC" + (100 + n));
        assertEquals(expectedIds, incidentIds);

        // ---- phase 3: every subscriber reports its status at the same time
        List<Callable<Void>> reports = new ArrayList<>();
        for (int i = 0; i < subscribers; i++) {
            DatagramSocket socket = subscriberSockets.get(i);
            String name = "Sub" + i;
            reports.add(() -> {
                assertEquals(MessageType.STATUS_RECORDED,
                        send(udp, socket, "STATUS_REPORT|" + rq() + "|INC100|" + name + "|SAFE").type());
                return null;
            });
        }
        runAll(pool, reports);
        pool.shutdown();

        // Every subscriber now sees all 20 alerts.
        assertEquals(Integer.toString(totalAlerts), sync(server.tcpPort(), "Sub0", totalAlerts + 1).get(0).field(1));

        // ---- phase 4: restart and compare
        server.close();
        String beforeRestart = Files.readString(stateFile);

        assertEquals(subscribers + publishers, count(beforeRestart, "CLIENT|"));
        assertEquals(totalAlerts, count(beforeRestart, "INCIDENT|"));
        assertEquals(subscribers, count(beforeRestart, "STATUS|"));
        assertTrue(beforeRestart.contains("NEXT_INCIDENT|" + (100 + totalAlerts)));

        CenrsServer restarted = startInProcess(stateFile);
        InetSocketAddress newUdp = new InetSocketAddress("127.0.0.1", restarted.udpPort());
        DatagramSocket sub0 = subscriberSockets.get(0);
        send(newUdp, sub0, register("Sub0", "SUBSCRIBER", sub0));

        assertEquals(Integer.toString(totalAlerts),
                sync(restarted.tcpPort(), "Sub0", totalAlerts + 1).get(0).field(1));

        System.out.println(
                "[LOAD] " + publishers + " publishers x " + alertsPerPublisher + " alerts + "
                        + subscribers + " concurrent TCP syncs in " + loadMillis + " ms"
        );
    }

    // ------------------------------------------------------------- helpers

    private void runAll(ExecutorService pool, List<Callable<Void>> tasks) throws Exception {
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) futures.add(pool.submit(task));
        for (Future<Void> future : futures) future.get(30, TimeUnit.SECONDS);
    }

    private static int count(String snapshot, String prefix) {
        return (int) snapshot.lines().filter(line -> line.startsWith(prefix)).count();
    }

    private String rq() {
        return Long.toString(requestNumbers.getAndIncrement());
    }

    private String register(String name, String role, DatagramSocket socket) {
        return "REGISTER|" + rq() + "|" + name + "|" + role + "|127.0.0.1|"
                + socket.getLocalPort() + "|" + (socket.getLocalPort() + 1);
    }

    private CenrsServer startInProcess(Path stateFile) throws Exception {
        CenrsServer server = new CenrsServer(0, 0, stateFile);
        synchronized (toClose) {
            toClose.add(server);
        }
        Thread runner = new Thread(() -> {
            try {
                server.run();
            } catch (Exception exception) {
                System.err.println(exception.getMessage());
            }
        });
        runner.start();
        waitForTcp(server.tcpPort());
        return server;
    }

    private static void waitForTcp(int port) throws Exception {
        for (int i = 0; i < 300; i++) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException notYet) {
                Thread.sleep(50);
            }
        }
        throw new IOException("Server did not start on TCP port " + port);
    }

    private DatagramSocket udpClient() throws Exception {
        DatagramSocket socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        socket.setSoTimeout(5_000);
        synchronized (toClose) {
            toClose.add(socket);
        }
        return socket;
    }

    private ControlMessage send(InetSocketAddress server, DatagramSocket socket, String message)
            throws Exception {

        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(bytes, bytes.length, server));

        while (true) {
            DatagramPacket reply = new DatagramPacket(new byte[4097], 4097);
            socket.receive(reply);
            ControlMessage response =
                    codec.decode(Arrays.copyOf(reply.getData(), reply.getLength()));

            if (response.type() != MessageType.ALERT) {
                return response;
            }
        }
    }

    private List<ControlMessage> sync(int tcpPort, String name, int lines) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", tcpPort), 2_000);
            socket.setSoTimeout(5_000);

            OutputStream output = socket.getOutputStream();
            output.write(("GET_ACTIVE_ALERTS|" + rq() + "|" + name + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            List<ControlMessage> answer = new ArrayList<>();
            for (int i = 0; i < lines; i++) {
                answer.add(tcp.read(socket.getInputStream()));
            }
            return answer;
        }
    }

    private static int freeUdpPort() throws IOException {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int freeTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String javaExecutable() {
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }
}
