package com.cenrs.contract.port;

import com.cenrs.contract.ClientProfile;
import java.util.List;
import java.util.Optional;

public interface RegistryPort {
    boolean addIfAbsent(ClientProfile client);

    Optional<ClientProfile> findByName(String name);

    List<ClientProfile> findAll();

    boolean remove(String name);
}