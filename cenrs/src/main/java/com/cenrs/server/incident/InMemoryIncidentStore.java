package com.cenrs.server.incident;

import com.cenrs.contract.Incident;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.port.IncidentStorePort;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


public final class InMemoryIncidentStore
        implements IncidentStorePort {

    private final Map<String, Incident> incidents =
            new ConcurrentHashMap<>();

    // Everyone who was notified about an incident, so ALL_CLEAR can reach them later.
    private final Map<String, Set<String>> recipients =
            new ConcurrentHashMap<>();

    @Override
    public boolean create(Incident incident) {
        Objects.requireNonNull(incident, "incident cannot be null");

        return incidents.putIfAbsent(
                incident.incidentId(),
                incident
        ) == null;
    }

    @Override
    public Optional<Incident> findById(String incidentId) {
        Objects.requireNonNull(incidentId, "incident ID cannot be null");

        return Optional.ofNullable(incidents.get(incidentId));
    }

    @Override
    public List<Incident> findActive() {
        return findAll()
                .stream()
                .filter(
                        incident ->
                                incident.status() == IncidentStatus.ACTIVE
                )
                .toList();
    }

    /** All incidents (active and closed), sorted by ID. Used for persistence. */
    public List<Incident> findAll() {
        return incidents.values()
                .stream()
                .sorted(Comparator.comparing(Incident::incidentId))
                .toList();
    }

    @Override
    public boolean replaceIfCurrent(
            Incident current,
            Incident updated
    ) {
        Objects.requireNonNull(current, "current incident cannot be null");
        Objects.requireNonNull(updated, "updated incident cannot be null");

        if (!current.incidentId().equals(updated.incidentId())) {
            return false;
        }

        // Only succeeds if nobody changed the incident in the meantime.
        return incidents.replace(
                current.incidentId(),
                current,
                updated
        );
    }

    @Override
    public void recordRecipients(
            String incidentId,
            Set<String> clientNames
    ) {
        Objects.requireNonNull(incidentId, "incident ID cannot be null");
        Objects.requireNonNull(clientNames, "client names cannot be null");

        if (!incidents.containsKey(incidentId)) {
            throw new IllegalArgumentException(
                    "Unknown incident " + incidentId
            );
        }

        Set<String> names = Set.copyOf(clientNames);

        // Keep earlier recipients and add the new ones (no duplicates).
        recipients.compute(
                incidentId,
                (id, previous) -> {
                    Set<String> combined = new HashSet<>(
                            previous == null ? Set.of() : previous
                    );
                    combined.addAll(names);
                    return Set.copyOf(combined);
                }
        );
    }

    @Override
    public Set<String> findRecipients(String incidentId) {
        Objects.requireNonNull(incidentId, "incident ID cannot be null");

        return recipients.getOrDefault(incidentId, Set.of());
    }
}
