package com.cenrs.server.persistence;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.Subscription;
import com.cenrs.contract.fake.FakePorts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ServerStateStoreTest {
    @Rule
    public TemporaryFolder folder =
            new TemporaryFolder();

    @Test
    public void profilesAndSubscriptionsSurviveRestart()
            throws Exception {

        Path stateFile = folder
                .getRoot()
                .toPath()
                .resolve("cenrs-state.txt");

        FakePorts.Registry originalRegistry =
                new FakePorts.Registry();

        FakePorts.Subscriptions originalSubscriptions =
                new FakePorts.Subscriptions();

        ClientProfile alice = client(
                "Alice",
                ClientRole.SUBSCRIBER,
                5001
        );

        ClientProfile bob = client(
                "Bob",
                ClientRole.BOTH,
                5002
        );

        originalRegistry.addIfAbsent(alice);
        originalRegistry.addIfAbsent(bob);

        originalSubscriptions.add(
                "Alice",
                new Subscription(
                        CampusLocation.HALL,
                        AlertCategory.FIRE
                )
        );

        originalSubscriptions.add(
                "Bob",
                new Subscription(
                        CampusLocation.ALL,
                        AlertCategory.WEATHER
                )
        );

        ServerStateStore originalStore =
                new ServerStateStore(
                        originalRegistry,
                        originalSubscriptions,
                        new FilePersistence(stateFile)
                );

        originalStore.save();

        FakePorts.Registry restoredRegistry =
                new FakePorts.Registry();

        FakePorts.Subscriptions restoredSubscriptions =
                new FakePorts.Subscriptions();

        ServerStateStore restoredStore =
                new ServerStateStore(
                        restoredRegistry,
                        restoredSubscriptions,
                        new FilePersistence(stateFile)
                );

        restoredStore.restore();

        assertEquals(
                alice,
                restoredRegistry
                        .findByName("Alice")
                        .orElseThrow()
        );

        assertEquals(
                bob,
                restoredRegistry
                        .findByName("Bob")
                        .orElseThrow()
        );

        assertEquals(
                Set.of(
                        new Subscription(
                                CampusLocation.HALL,
                                AlertCategory.FIRE
                        )
                ),
                restoredSubscriptions
                        .findByClient("Alice")
        );

        assertEquals(
                Set.of(
                        new Subscription(
                                CampusLocation.ALL,
                                AlertCategory.WEATHER
                        )
                ),
                restoredSubscriptions
                        .findByClient("Bob")
        );
    }

    @Test
    public void newerSnapshotReplacesOlderState()
            throws Exception {

        Path stateFile = folder
                .getRoot()
                .toPath()
                .resolve("cenrs-state.txt");

        FakePorts.Registry registry =
                new FakePorts.Registry();

        FakePorts.Subscriptions subscriptions =
                new FakePorts.Subscriptions();

        ServerStateStore store =
                new ServerStateStore(
                        registry,
                        subscriptions,
                        new FilePersistence(stateFile)
                );

        registry.addIfAbsent(
                client(
                        "Alice",
                        ClientRole.SUBSCRIBER,
                        5001
                )
        );

        store.save();

        registry.remove("Alice");

        registry.addIfAbsent(
                client(
                        "Bob",
                        ClientRole.BOTH,
                        5002
                )
        );

        store.save();

        FakePorts.Registry restoredRegistry =
                new FakePorts.Registry();

        ServerStateStore restoredStore =
                new ServerStateStore(
                        restoredRegistry,
                        new FakePorts.Subscriptions(),
                        new FilePersistence(stateFile)
                );

        restoredStore.restore();

        assertFalse(
                restoredRegistry
                        .findByName("Alice")
                        .isPresent()
        );

        assertTrue(
                restoredRegistry
                        .findByName("Bob")
                        .isPresent()
        );
    }

    @Test
    public void corruptedSnapshotIsRejectedBeforeRestore()
            throws Exception {

        Path stateFile = folder
                .getRoot()
                .toPath()
                .resolve("cenrs-state.txt");

        Files.writeString(
                stateFile,
                "not-a-valid-snapshot"
        );

        FakePorts.Registry registry =
                new FakePorts.Registry();

        ServerStateStore store =
                new ServerStateStore(
                        registry,
                        new FakePorts.Subscriptions(),
                        new FilePersistence(stateFile)
                );

        try {
            store.restore();
            fail("Expected corrupted state to be rejected");
        } catch (IOException expected) {
            assertTrue(registry.findAll().isEmpty());
        }
    }

    private ClientProfile client(
            String name,
            ClientRole role,
            int udpPort
    ) {
        return new ClientProfile(
                name,
                role,
                "127.0.0.1",
                udpPort,
                6000 + udpPort
        );
    }
}