package com.cenrs.client.sync;

import com.cenrs.contract.IncidentStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The incidents a client currently knows about.
 *
 * Version rule (protocol.md):
 *   higher Version -> replaces the old state
 *   same Version   -> duplicate, ignored
 *   lower Version  -> stale, ignored
 *
 * Thread-safe: UDP notifications and TCP synchronization may update it at the same time.
 */
public final class ClientIncidentState {

    public enum Outcome {
        NEW,
        UPDATED,
        DUPLICATE,
        STALE
    }

    private final Map<String, KnownIncident> incidents = new HashMap<>();

    /** Applies one incident state using the version rule. */
    public synchronized Outcome apply(KnownIncident incident) {
        Objects.requireNonNull(incident, "incident cannot be null");

        KnownIncident current = incidents.get(incident.incidentId());

        if (current == null) {
            incidents.put(incident.incidentId(), incident);
            return Outcome.NEW;
        }

        if (incident.version() == current.version()) {
            return Outcome.DUPLICATE;
        }

        if (incident.version() < current.version()) {
            return Outcome.STALE;
        }

        incidents.put(incident.incidentId(), incident);
        return Outcome.UPDATED;
    }

    /**
     * Merges the server's list of active incidents after a (re)connection.
     *
     * An incident the client still thinks is ACTIVE but the server did not return
     * was closed (or stopped matching our subscriptions) while we were away:
     * it is removed from the active list.
     */
    public synchronized SyncReport reconcile(List<KnownIncident> serverActive) {
        Objects.requireNonNull(serverActive, "server list cannot be null");

        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<String> noLongerActive = new ArrayList<>();
        Set<String> serverIds = new HashSet<>();

        for (KnownIncident incident : serverActive) {
            serverIds.add(incident.incidentId());

            switch (apply(incident)) {
                case NEW -> added.add(incident.incidentId());
                case UPDATED -> updated.add(incident.incidentId());
                case DUPLICATE, STALE -> unchanged.add(incident.incidentId());
            }
        }

        for (KnownIncident local : List.copyOf(incidents.values())) {
            if (
                    local.status() == IncidentStatus.ACTIVE
                    && !serverIds.contains(local.incidentId())
            ) {
                incidents.remove(local.incidentId());
                noLongerActive.add(local.incidentId());
            }
        }

        return new SyncReport(
                sorted(added), sorted(updated), sorted(unchanged), sorted(noLongerActive)
        );
    }

    public synchronized Optional<KnownIncident> find(String incidentId) {
        return Optional.ofNullable(incidents.get(incidentId));
    }

    public synchronized List<KnownIncident> activeIncidents() {
        return incidents.values()
                .stream()
                .filter(incident -> incident.status() == IncidentStatus.ACTIVE)
                .sorted(Comparator.comparing(KnownIncident::incidentId))
                .toList();
    }

    private static List<String> sorted(List<String> ids) {
        return ids.stream().sorted().toList();
    }

    /** What changed during one synchronization. */
    public record SyncReport(
            List<String> added,
            List<String> updated,
            List<String> unchanged,
            List<String> noLongerActive
    ) {
        @Override
        public String toString() {
            return "added=" + added
                    + " updated=" + updated
                    + " unchanged=" + unchanged
                    + " noLongerActive=" + noLongerActive;
        }
    }
}
