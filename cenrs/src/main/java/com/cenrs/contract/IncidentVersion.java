package com.cenrs.contract;

public record IncidentVersion(
    String incidentId,
    long version
) {
    public IncidentVersion {
        incidentId = ContractValidation.requiredText(
            incidentId,
            "incident ID",
            128
        );
        version = ContractValidation.positive(version, "version");
    }
}