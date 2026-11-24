package com.cenrs.contract.port;

import com.cenrs.contract.ClientProfile;
import java.util.List;
import java.util.Optional;

public interface RegistryPort {
    boolean addIfAbsent(ClientProfile client);

    Optional<ClientProfile> findByName(String name);

    List<ClientProfile> findAll();

    boolean remove(String name);

    /**
     * Adds a client restored from the snapshot as INACTIVE:
     * it stays inactive until it registers again (see protocol.md, restart rules).
     */
    default boolean addInactive(ClientProfile client) {
        return addIfAbsent(client);
    }

    /**
     * Reactivates an inactive client that registers again after a restart,
     * updating its endpoint. Returns false if the name is unknown or already active.
     */
    default boolean reactivate(ClientProfile client) {
        return false;
    }

    /** True if the client is registered and active (not waiting to re-register). */
    default boolean isActive(String name) {
        return findByName(name).isPresent();
    }
}
