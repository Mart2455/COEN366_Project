package com.cenrs.contract;

import java.util.Objects;

public record StatusReport(
    String incidentId,
    String clientName,
    SafetyStatus status
) {
    public StatusReport {
        incidentId = ContractValidation.requiredText(
            incidentId,
            "incident ID",
            128
        );
        clientName = ContractValidation.requiredText(
            clientName,
            "client name",
            64
        );
        status = Objects.requireNonNull(
            status,
            "status cannot be null"
        );
    }
}