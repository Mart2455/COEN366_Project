package com.cenrs.contract.port;

import com.cenrs.contract.Incident;
import java.util.List;
import java.util.Optional;

public interface IncidentStorePort {
    boolean create(Incident incident);

    Optional<Incident> findById(String incidentId);

    List<Incident> findActive();

    boolean replaceIfCurrent(Incident current, Incident updated);
}