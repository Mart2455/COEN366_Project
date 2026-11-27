package com.cenrs.contract.port;

import com.cenrs.contract.StatusReport;

import java.util.Optional;

public interface StatusStorePort {
    void saveLatest(StatusReport report);

    Optional<StatusReport> findLatest(
        String incidentId,
        String clientName
    );
}
