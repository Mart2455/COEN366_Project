package com.cenrs.server.incident;

import com.cenrs.contract.port.IncidentEventSinkPort;
import com.cenrs.contract.port.IncidentIdGeneratorPort;
import com.cenrs.contract.port.IncidentStorePort;
import com.cenrs.contract.port.RegistryPort;

import java.util.Objects;

public class IncidentService {
    private final IncidentStorePort store;
    private final RegistryPort registry;
    private final IncidentIdGeneratorPort idGenerator;
    private final IncidentEventSinkPort eventSink;

    public IncidentService(
            IncidentStorePort store,
            RegistryPort registry,
            IncidentIdGeneratorPort idGenerator,
            IncidentEventSinkPort eventSink
    ) {
        this.store = Objects.requireNonNull(store);
        this.registry = Objects.requireNonNull(registry);
        this.idGenerator = Objects.requireNonNull(idGenerator);
        this.eventSink = Objects.requireNonNull(eventSink);
    }
}
