package com.cenrs.server.sync;

import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.Incident;
import com.cenrs.contract.Subscription;
import com.cenrs.contract.port.IncidentStorePort;
import com.cenrs.contract.port.RegistryPort;
import com.cenrs.contract.port.SubscriptionPort;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.server.incident.SequentialIncidentIdGenerator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Answers GET_ACTIVE_ALERTS (spec 2.10).
 *
 * Request:  GET_ACTIVE_ALERTS|RQ#|Name
 * Response: ACTIVE_ALERTS|RQ#|Number_Alerts
 *           followed by Number_Alerts lines:
 *           ACTIVE_ALERT|Incident_ID|Version|Category|Location|Severity|Status|Title|Description|Attachment
 * Denial:   ACTIVE_ALERTS_DENIED|RQ#|Reason
 *
 * This class only builds the answer. SyncTcpServer handles the sockets.
 */
public final class ActiveAlertSyncService {

    private final RegistryPort registry;
    private final SubscriptionPort subscriptions;
    private final IncidentStorePort incidents;

    public ActiveAlertSyncService(
            RegistryPort registry,
            SubscriptionPort subscriptions,
            IncidentStorePort incidents
    ) {
        this.registry = Objects.requireNonNull(registry);
        this.subscriptions = Objects.requireNonNull(subscriptions);
        this.incidents = Objects.requireNonNull(incidents);
    }

    /** Returns every message to send back, in order. */
    public List<ControlMessage> handle(ControlMessage request) {
        Objects.requireNonNull(request, "request cannot be null");

        if (request.type() != MessageType.GET_ACTIVE_ALERTS) {
            throw new IllegalArgumentException(
                    "Not a GET_ACTIVE_ALERTS request: "
                            + request.type().wireName()
            );
        }

        String requestNumber = request.field(0);
        String name = request.field(1);

        if (!isPositiveRequestNumber(requestNumber)) {
            return denied(requestNumber, "Invalid request number");
        }

        Optional<ClientProfile> client = registry.findByName(name);

        if (client.isEmpty()) {
            return denied(requestNumber, "Unknown client");
        }

        // After a server restart, a client must register again first.
        if (!registry.isActive(name)) {
            return denied(requestNumber, "Client not registered");
        }

        // Only subscribers synchronize alerts (protocol.md, Authorization).
        if (client.get().role() == ClientRole.PUBLISHER) {
            return denied(requestNumber, "Unauthorized role");
        }

        List<Incident> matching = matchingActiveIncidents(name);

        List<ControlMessage> response = new ArrayList<>();
        response.add(
                ControlMessage.of(
                        MessageType.ACTIVE_ALERTS,
                        requestNumber,
                        Integer.toString(matching.size())
                )
        );

        for (Incident incident : matching) {
            response.add(record(incident));
        }

        return response;
    }

    /** ACTIVE incidents that match at least one of the client's subscriptions. */
    private List<Incident> matchingActiveIncidents(String name) {
        Set<Subscription> clientSubscriptions =
                subscriptions.findByClient(name);

        return incidents.findActive()
                .stream()
                .filter(
                        incident -> clientSubscriptions
                                .stream()
                                .anyMatch(
                                        subscription -> subscription.matches(
                                                incident.location(),
                                                incident.category()
                                        )
                                )
                )
                // Oldest incident first (INC99 before INC100).
                .sorted(
                        Comparator
                                .comparingLong(
                                        (Incident incident) ->
                                                SequentialIncidentIdGenerator
                                                        .numberOf(incident.incidentId())
                                )
                                .thenComparing(Incident::incidentId)
                )
                .toList();
    }

    private static ControlMessage record(Incident incident) {
        return ControlMessage.of(
                MessageType.ACTIVE_ALERT,
                incident.incidentId(),
                Long.toString(incident.version()),
                incident.category().name(),
                incident.location().name(),
                incident.severity().name(),
                incident.status().name(),
                incident.title(),
                incident.description(),
                incident.attachment()
        );
    }

    private static List<ControlMessage> denied(
            String requestNumber,
            String reason
    ) {
        return List.of(
                ControlMessage.of(
                        MessageType.ACTIVE_ALERTS_DENIED,
                        requestNumber,
                        reason
                )
        );
    }

    private static boolean isPositiveRequestNumber(String value) {
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
