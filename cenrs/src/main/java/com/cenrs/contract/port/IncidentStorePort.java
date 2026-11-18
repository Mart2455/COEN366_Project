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

    /**
     * Atomically union names into an existing incident's recipient history.
     * Reject unknown IDs. Preserve history through updates and closure.
     * Production adapters must persist history with incident state.
     */
    void recordRecipients(String incidentId, Set<String> clientNames);
    
    /** Return an immutable snapshot, or an empty set for an unknown ID. */
    Set<String> findRecipients(String incidentId);
}