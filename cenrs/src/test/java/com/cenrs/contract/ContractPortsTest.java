package com.cenrs.contract;

import static com.cenrs.contract.fixture.ContractFixtures.REGISTER_FRAME;
import static com.cenrs.contract.fixture.ContractFixtures.activeIncident;
import static com.cenrs.contract.fixture.ContractFixtures.hallFireSubscription;
import static com.cenrs.contract.fixture.ContractFixtures.subscriber;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cenrs.contract.fake.FakePorts;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class ContractPortsTest {
    @Test
    public void registryRejectsDuplicateClientNames() {
        FakePorts.Registry registry = new FakePorts.Registry();

        assertTrue(registry.addIfAbsent(subscriber()));
        assertFalse(registry.addIfAbsent(subscriber()));
        assertEquals(
            subscriber(),
            registry.findByName("Alice").orElseThrow()
        );
    }

    @Test
    public void subscriptionsAreStoredByClient() {
        FakePorts.Subscriptions subscriptions =
            new FakePorts.Subscriptions();

        assertTrue(
            subscriptions.add(
                "Alice",
                hallFireSubscription()
            )
        );

        assertTrue(
            subscriptions
                .findByClient("Alice")
                .contains(hallFireSubscription())
        );
    }

    @Test
    public void incidentReplacementRejectsStaleVersions() {
        FakePorts.Incidents incidents = new FakePorts.Incidents();
        Incident current = activeIncident();

        Incident updated = new Incident(
            current.incidentId(),
            2,
            current.category(),
            current.location(),
            Severity.WARNING,
            current.status(),
            current.title(),
            "Smoke is now under control",
            current.attachment(),
            current.publisherName()
        );

        assertTrue(incidents.create(current));
        assertTrue(
            incidents.replaceIfCurrent(current, updated)
        );
        assertFalse(
            incidents.replaceIfCurrent(current, updated)
        );
    }

    @Test
    public void schedulerRunsPendingWorkOnce() {
        FakePorts.Scheduler scheduler =
            new FakePorts.Scheduler();
        AtomicBoolean executed = new AtomicBoolean();

        scheduler.schedule(
            Duration.ofSeconds(2),
            () -> executed.set(true)
        );

        scheduler.runPending();

        assertTrue(executed.get());
    }

    @Test
    public void externalBoundariesWorkWithoutRealResources()
        throws Exception {

        FakePorts.Transport transport =
            new FakePorts.Transport();
        byte[] message =
            REGISTER_FRAME.getBytes(StandardCharsets.UTF_8);

        transport.send(
            new InetSocketAddress("127.0.0.1", 5000),
            message
        );

        assertArrayEquals(
            message,
            transport.sentMessages().get(0).message()
        );

        FakePorts.Persistence persistence =
            new FakePorts.Persistence();
        persistence.save(message);

        assertArrayEquals(
            message,
            persistence.load().orElseThrow()
        );

        FakePorts.FileCatalog catalog =
            new FakePorts.FileCatalog();
        Path path = Path.of("attachments", "map.pdf");

        catalog.add("Bob", "INC-1", "map.pdf", path);

        assertEquals(
            path,
            catalog
                .find("Bob", "INC-1", "map.pdf")
                .orElseThrow()
        );
    }
}