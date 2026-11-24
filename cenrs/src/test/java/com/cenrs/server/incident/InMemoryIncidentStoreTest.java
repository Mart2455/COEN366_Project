package com.cenrs.server.incident;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.Incident;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.Severity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class InMemoryIncidentStoreTest {

    @Test
    public void createRejectsDuplicateId() {
        InMemoryIncidentStore store = new InMemoryIncidentStore();

        assertTrue(store.create(incident("INC100", 1, IncidentStatus.ACTIVE)));
        assertFalse(store.create(incident("INC100", 1, IncidentStatus.ACTIVE)));
    }

    @Test
    public void findActiveSkipsClosedIncidents() {
        InMemoryIncidentStore store = new InMemoryIncidentStore();
        Incident active = incident("INC100", 1, IncidentStatus.ACTIVE);
        store.create(active);
        store.create(incident("INC101", 2, IncidentStatus.CLOSED));

        assertEquals(List.of(active), store.findActive());
        assertEquals(2, store.findAll().size());
    }

    @Test
    public void replaceOnlyWorksWithTheCurrentVersion() {
        InMemoryIncidentStore store = new InMemoryIncidentStore();
        Incident v1 = incident("INC100", 1, IncidentStatus.ACTIVE);
        Incident v2 = incident("INC100", 2, IncidentStatus.ACTIVE);
        Incident v3 = incident("INC100", 3, IncidentStatus.CLOSED);
        store.create(v1);

        assertTrue(store.replaceIfCurrent(v1, v2));
        // v1 is now stale, so a second writer using it loses.
        assertFalse(store.replaceIfCurrent(v1, v3));
        assertEquals(v2, store.findById("INC100").orElseThrow());
    }

    @Test
    public void recipientsAreMergedWithoutDuplicates() {
        InMemoryIncidentStore store = new InMemoryIncidentStore();
        store.create(incident("INC100", 1, IncidentStatus.ACTIVE));

        store.recordRecipients("INC100", Set.of("Alice", "Bob"));
        store.recordRecipients("INC100", Set.of("Bob", "Carol"));

        assertEquals(Set.of("Alice", "Bob", "Carol"), store.findRecipients("INC100"));
        assertEquals(Set.of(), store.findRecipients("INC999"));
    }

    @Test
    public void recipientsForUnknownIncidentAreRejected() {
        InMemoryIncidentStore store = new InMemoryIncidentStore();

        try {
            store.recordRecipients("INC999", Set.of("Alice"));
            fail("Expected an unknown incident");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("INC999"));
        }
    }

    @Test
    public void idGeneratorNeverGivesTheSameIdTwice() throws Exception {
        SequentialIncidentIdGenerator ids = new SequentialIncidentIdGenerator();
        Set<String> seen = java.util.Collections.synchronizedSet(new HashSet<>());

        // 8 threads x 500 IDs at the same time.
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int thread = 0; thread < 8; thread++) {
            pool.submit(() -> {
                for (int i = 0; i < 500; i++) {
                    seen.add(ids.nextId());
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(4000, seen.size());
        assertEquals(4100, ids.peekNextNumber());
    }

    @Test
    public void idGeneratorNeverMovesBackwards() {
        SequentialIncidentIdGenerator ids = new SequentialIncidentIdGenerator();

        ids.advanceTo(500);
        ids.advanceTo(200);

        assertEquals("INC500", ids.nextId());
        assertEquals(102, SequentialIncidentIdGenerator.numberOf("INC102"));
        assertEquals(-1, SequentialIncidentIdGenerator.numberOf("FIRE-1"));
    }

    private static Incident incident(String id, long version, IncidentStatus status) {
        return new Incident(
                id, version, AlertCategory.FIRE, CampusLocation.HALL,
                Severity.CRITICAL, status, "Fire Alarm", "Evacuate Building",
                "NONE", "Security"
        );
    }
}
