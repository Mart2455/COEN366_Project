package com.cenrs.contract.port;

import com.cenrs.contract.Incident;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface IncidentStorePort {
    boolean create(Incident incident);

    Optional<Incident> findById(String incidentId);

    List<Incident> findActive();

    boolean replaceIfCurrent(Incident current, Incident updated);

    void recordRecipients(String incidentId, Set<String> clientNames);
    
    Set<String> findRecipients(String incidentId);
}