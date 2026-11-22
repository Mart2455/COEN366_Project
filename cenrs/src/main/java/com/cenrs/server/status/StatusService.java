package com.cenrs.server.status;

import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.SafetyStatus;
import com.cenrs.contract.StatusReport;
import com.cenrs.contract.port.IncidentStorePort;
import com.cenrs.contract.port.RegistryPort;
import com.cenrs.contract.port.StatusStorePort;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;

public final class StatusService {
    private final RegistryPort registry;
    private final IncidentStorePort incidents;
    private final StatusStorePort statuses;

    public StatusService(
        RegistryPort registry,
        IncidentStorePort incidents,
        StatusStorePort statuses
    ) {
        this.registry = Objects.requireNonNull(registry);
        this.incidents = Objects.requireNonNull(incidents);
        this.statuses = Objects.requireNonNull(statuses);
    }

    public ControlMessage report(
        ControlMessage request,
        InetSocketAddress sender
    ) {
        Objects.requireNonNull(
            request,
            "request cannot be null"
        );
        Objects.requireNonNull(
            sender,
            "sender cannot be null"
        );

        if (request.type() != MessageType.STATUS_REPORT) {
            throw new IllegalArgumentException(
                "Expected STATUS_REPORT"
            );
        }

        String requestNumber = request.field(0);

        if (!isPositiveRequestNumber(requestNumber)) {
            return rejected(
                requestNumber,
                "Invalid request number"
            );
        }

        Optional<ClientProfile> registeredClient =
            registry.findAll()
                .stream()
                .filter(
                    client ->
                        sameEndpoint(client, sender)
                )
                .findFirst();

        if (registeredClient.isEmpty()) {
            return rejected(
                requestNumber,
                "Client is not registered"
            );
        }

        ClientProfile client = registeredClient.get();

        if (!client.role().canSubscribe()) {
            return rejected(
                requestNumber,
                "Role cannot report status"
            );
        }

        String clientName = request.field(2);

        if (!client.name().equals(clientName)) {
            return rejected(
                requestNumber,
                "Client name does not match sender"
            );
        }

        String incidentId = request.field(1);
        var incident = incidents.findById(incidentId);

        if (incident.isEmpty()) {
            return rejected(
                requestNumber,
                "Unknown incident"
            );
        }

        if (
            incident.get().status()
                != IncidentStatus.ACTIVE
        ) {
            return rejected(
                requestNumber,
                "Incident is closed"
            );
        }

        SafetyStatus safetyStatus;

        try {
            safetyStatus = SafetyStatus.valueOf(
                request.field(3)
            );
        } catch (IllegalArgumentException exception) {
            return rejected(
                requestNumber,
                "Invalid status"
            );
        }

        statuses.saveLatest(
            new StatusReport(
                incidentId,
                clientName,
                safetyStatus
            )
        );

        return ControlMessage.of(
            MessageType.STATUS_RECORDED,
            requestNumber
        );
    }

    private boolean sameEndpoint(
        ClientProfile client,
        InetSocketAddress sender
    ) {
        return sender.getAddress() != null
            && sender.getAddress()
                .getHostAddress()
                .equals(client.ipAddress())
            && sender.getPort() == client.udpPort();
    }

    private boolean isPositiveRequestNumber(
        String value
    ) {
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private ControlMessage rejected(
        String requestNumber,
        String reason
    ) {
        return ControlMessage.of(
            MessageType.STATUS_REJECTED,
            requestNumber,
            reason
        );
    }
}