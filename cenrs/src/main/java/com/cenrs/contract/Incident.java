package com.cenrs.contract;

import java.util.Objects;

public record Incident(
    String incidentId,
    long version,
    AlertCategory category,
    CampusLocation location,
    Severity severity,
    IncidentStatus status,
    String title,
    String description,
    String attachment,
    String publisherName
) {
    public Incident {
        incidentId = ContractValidation.requiredText(
            incidentId,
            "incident ID",
            128
        );
        version = ContractValidation.positive(version, "version");
        category = Objects.requireNonNull(
            category,
            "category cannot be null"
        );
        location = Objects.requireNonNull(
            location,
            "location cannot be null"
        );
        severity = Objects.requireNonNull(
            severity,
            "severity cannot be null"
        );
        status = Objects.requireNonNull(
            status,
            "status cannot be null"
        );
        title = ContractValidation.requiredText(title, "title", 128);
        description = ContractValidation.requiredText(
            description,
            "description",
            1024
        );
        attachment = ContractValidation.requiredText(
            attachment,
            "attachment",
            255
        );
        publisherName = ContractValidation.requiredText(
            publisherName,
            "publisher name",
            64
        );
    }

    public boolean hasAttachment() {
        return !attachment.equals("NONE");
    }
}