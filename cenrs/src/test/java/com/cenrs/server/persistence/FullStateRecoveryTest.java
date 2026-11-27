package com.cenrs.server.persistence;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.Incident;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.SafetyStatus;
import com.cenrs.contract.Severity;
import com.cenrs.contract.StatusReport;
import com.cenrs.contract.Subscription;
import com.cenrs.server.delivery.DeliveryKey;
import com.cenrs.server.delivery.DeliveryState;
import com.cenrs.server.delivery.InMemoryDeliveryStateRepository;
import com.cenrs.server.incident.InMemoryIncidentStore;
import com.cenrs.server.incident.SequentialIncidentIdGenerator;
import com.cenrs.server.state.ServerState;
import com.cenrs.server.status.InMemoryStatusStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Issue 18: everything the spec lists must survive a server crash. */
public class FullStateRecoveryTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final Instant TIME = Instant.ofEpochMilli(1_795_000_000_000L);

    @Test
    public void allServerStateSurvivesRestart() throws Exception {
        Path stateFile = stateFile();

        // ---- before the crash
        Server before = new Server(stateFile);

        ClientProfile alice = client("Alice", ClientRole.SUBSCRIBER, 5001);
        ClientProfile security = client("Security", ClientRole.PUBLISHER, 5002);
        before.state.addIfAbsent(alice);
        before.state.addIfAbsent(security);
        before.state.add(
                "Alice",
                new Subscription(CampusLocation.HALL, AlertCategory.FIRE)
        );

        Incident fire = incident(
                before.ids.nextId(), 2, IncidentStatus.ACTIVE,
                "Fire Controlled", "evacuation_map.pdf"
        );
        Incident power = incident(
                before.ids.nextId(), 3, IncidentStatus.CLOSED,
                "Power Restored", "NONE"
        );
        before.incidents.create(fire);
        before.incidents.create(power);
        before.incidents.recordRecipients(fire.incidentId(), Set.of("Alice"));

        before.statuses.saveLatest(
                new StatusReport(fire.incidentId(), "Alice", SafetyStatus.SAFE)
        );

        DeliveryState confirmed = delivery(
                83, fire.incidentId(), 1, DeliveryState.Status.CONFIRMED, 1
        );
        DeliveryState pending = delivery(
                118, fire.incidentId(), 2, DeliveryState.Status.PENDING, 2
        );
        before.deliveries.save(confirmed);
        before.deliveries.save(pending);

        before.store.save();

        // ---- after the crash: a brand-new server restores the file
        Server after = new Server(stateFile);
        after.store.restore();

        assertEquals(Optional.of(alice), after.state.findByName("Alice"));
        assertEquals(
                Set.of(new Subscription(CampusLocation.HALL, AlertCategory.FIRE)),
                after.state.findByClient("Alice")
        );

        // Active and closed incidents, with version, status and attachment + publisher.
        assertEquals(Optional.of(fire), after.incidents.findById(fire.incidentId()));
        assertEquals(Optional.of(power), after.incidents.findById(power.incidentId()));
        assertEquals(List.of(fire), after.incidents.findActive());
        assertEquals(Set.of("Alice"), after.incidents.findRecipients(fire.incidentId()));

        assertEquals(
                Optional.of(
                        new StatusReport(fire.incidentId(), "Alice", SafetyStatus.SAFE)
                ),
                after.statuses.findLatest(fire.incidentId(), "Alice")
        );

        assertEquals(Optional.of(confirmed), after.deliveries.find(confirmed.key()));
        // The timer is gone after a crash, so a PENDING delivery becomes UNCONFIRMED.
        assertEquals(
                DeliveryState.Status.UNCONFIRMED,
                after.deliveries.find(pending.key()).orElseThrow().status()
        );
    }

    @Test
    public void incidentIdsAreNotReusedAfterRestart() throws Exception {
        Path stateFile = stateFile();

        Server before = new Server(stateFile);
        assertEquals("INC100", before.ids.nextId());
        assertEquals("INC101", before.ids.nextId());
        before.store.save();

        Server after = new Server(stateFile);
        after.store.restore();

        assertEquals("INC102", after.ids.nextId());
    }

    @Test
    public void idCounterIsMovedPastRestoredIncidents() throws Exception {
        Path stateFile = stateFile();

        // A snapshot whose NEXT_INCIDENT is too low must still never reuse INC150.
        write(
                stateFile,
                "CENRS-STATE|2",
                "NEXT_INCIDENT|100",
                "INCIDENT|INC150|1|FIRE|HALL|CRITICAL|ACTIVE|Fire|Evacuate|NONE|Security"
        );

        Server after = new Server(stateFile);
        after.store.restore();

        assertEquals("INC151", after.ids.nextId());
    }

    @Test
    public void restoredClientsAreInactiveUntilTheyRegisterAgain() throws Exception {
        Path stateFile = stateFile();

        Server before = new Server(stateFile);
        before.state.addIfAbsent(client("Alice", ClientRole.SUBSCRIBER, 5001));
        assertTrue(before.state.isActive("Alice"));
        before.store.save();

        Server after = new Server(stateFile);
        after.store.restore();

        assertTrue(after.state.findByName("Alice").isPresent());
        assertFalse(after.state.isActive("Alice"));

        // Alice comes back on a new UDP port: her profile is reactivated and updated.
        ClientProfile aliceNewPort = client("Alice", ClientRole.SUBSCRIBER, 7001);
        assertTrue(after.state.reactivate(aliceNewPort));
        assertTrue(after.state.isActive("Alice"));
        assertEquals(Optional.of(aliceNewPort), after.state.findByName("Alice"));

        // A second registration for an active name is denied.
        assertFalse(after.state.reactivate(client("Alice", ClientRole.SUBSCRIBER, 7002)));
        assertFalse(after.state.addIfAbsent(client("Alice", ClientRole.SUBSCRIBER, 7002)));
    }

    @Test
    public void oldPhaseOneSnapshotsCanStillBeRestored() throws Exception {
        Path stateFile = stateFile();

        write(
                stateFile,
                "CENRS-STATE|1",
                "CLIENT|Alice|SUBSCRIBER|127.0.0.1|5001|6001",
                "SUBSCRIPTION|Alice|HALL|FIRE"
        );

        Server after = new Server(stateFile);
        after.store.restore();

        assertTrue(after.state.findByName("Alice").isPresent());
        assertEquals(1, after.state.findByClient("Alice").size());
    }

    @Test
    public void invalidSnapshotDoesNotChangeTheLiveState() throws Exception {
        Path stateFile = stateFile();

        // The status points to an incident that does not exist.
        write(
                stateFile,
                "CENRS-STATE|2",
                "CLIENT|Alice|SUBSCRIBER|127.0.0.1|5001|6001",
                "STATUS|INC999|Alice|SAFE"
        );

        Server after = new Server(stateFile);

        try {
            after.store.restore();
            fail("Expected an invalid snapshot");
        } catch (IOException expected) {
            // Nothing was applied: everything is validated before it is used.
            assertTrue(after.state.findAll().isEmpty());
        }
    }

    @Test
    public void malformedIncidentRecordIsRejected() throws Exception {
        Path stateFile = stateFile();

        write(
                stateFile,
                "CENRS-STATE|2",
                "INCIDENT|INC100|0|FIRE|HALL|CRITICAL|ACTIVE|Fire|Evacuate|NONE|Security"
        );

        try {
            new Server(stateFile).store.restore();
            fail("Expected an invalid incident version");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("incident"));
        }
    }

    // ------------------------------------------------------------- helpers

    /** The same objects a real Emergency Server would build. */
    private static final class Server {
        final ServerState state = new ServerState();
        final InMemoryIncidentStore incidents = new InMemoryIncidentStore();
        final SequentialIncidentIdGenerator ids = new SequentialIncidentIdGenerator();
        final InMemoryStatusStore statuses = new InMemoryStatusStore();
        final InMemoryDeliveryStateRepository deliveries =
                new InMemoryDeliveryStateRepository();
        final ServerStateStore store;

        Server(Path stateFile) {
            store = new ServerStateStore(
                    state, state, incidents, ids, statuses, deliveries,
                    new FilePersistence(stateFile)
            );
        }
    }

    private Path stateFile() {
        return folder.getRoot().toPath().resolve("cenrs-state.txt");
    }

    private static void write(Path file, String... lines) throws IOException {
        Files.write(
                file,
                (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8)
        );
    }

    private static ClientProfile client(String name, ClientRole role, int udpPort) {
        return new ClientProfile(name, role, "127.0.0.1", udpPort, udpPort + 1000);
    }

    private static Incident incident(
            String id,
            long version,
            IncidentStatus status,
            String title,
            String attachment
    ) {
        return new Incident(
                id, version, AlertCategory.FIRE, CampusLocation.HALL,
                Severity.WARNING, status, title, "Remain outside the building",
                attachment, "Security"
        );
    }

    private static DeliveryState delivery(
            long requestNumber,
            String incidentId,
            long version,
            DeliveryState.Status status,
            int attempts
    ) {
        return new DeliveryState(
                new DeliveryKey(requestNumber, incidentId, version, "Alice"),
                status,
                attempts,
                TIME
        );
    }
}
