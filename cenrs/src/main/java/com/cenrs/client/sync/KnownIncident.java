package com.cenrs.client.sync;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.Severity;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;

import java.util.Objects;

/** What a client knows about one incident (one ACTIVE_ALERT record). */
public record KnownIncident(
        String incidentId,
        long version,
        AlertCategory category,
        CampusLocation location,
        Severity severity,
        IncidentStatus status,
        String title,
        String description,
        String attachment
) {
    public KnownIncident {
        Objects.requireNonNull(incidentId, "incident ID cannot be null");
        Objects.requireNonNull(category, "category cannot be null");
        Objects.requireNonNull(location, "location cannot be null");
        Objects.requireNonNull(severity, "severity cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(title, "title cannot be null");
        Objects.requireNonNull(description, "description cannot be null");
        Objects.requireNonNull(attachment, "attachment cannot be null");

        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
    }

    /** Builds it from ACTIVE_ALERT|ID|Version|Category|Location|Severity|Status|Title|Description|Attachment */
    public static KnownIncident fromActiveAlert(ControlMessage record)
            throws ProtocolException {

        if (record.type() != MessageType.ACTIVE_ALERT) {
            throw new ProtocolException(
                    "Expected ACTIVE_ALERT but received " + record.type().wireName()
            );
        }

        try {
            return new KnownIncident(
                    record.field(0),
                    Long.parseLong(record.field(1)),
                    AlertCategory.valueOf(record.field(2)),
                    CampusLocation.valueOf(record.field(3)),
                    Severity.valueOf(record.field(4)),
                    IncidentStatus.valueOf(record.field(5)),
                    record.field(6),
                    record.field(7),
                    record.field(8)
            );
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(
                    "Invalid ACTIVE_ALERT record: " + exception.getMessage()
            );
        }
    }

    public KnownIncident closed(long closedVersion) {
        return new KnownIncident(
                incidentId, closedVersion, category, location, severity,
                IncidentStatus.CLOSED, title, description, attachment
        );
    }
}
