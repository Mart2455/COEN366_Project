package com.cenrs.server.status;

import com.cenrs.contract.StatusReport;
import com.cenrs.contract.port.StatusStorePort;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryStatusStore
    implements StatusStorePort {

    private final ConcurrentMap<StatusKey, StatusReport> reports =
        new ConcurrentHashMap<>();

    @Override
    public void saveLatest(StatusReport report) {
        Objects.requireNonNull(
            report,
            "report cannot be null"
        );

        reports.put(
            new StatusKey(
                report.incidentId(),
                report.clientName()
            ),
            report
        );
    }

    @Override
    public Optional<StatusReport> findLatest(
        String incidentId,
        String clientName
    ) {
        Objects.requireNonNull(
            incidentId,
            "incident ID cannot be null"
        );
        Objects.requireNonNull(
            clientName,
            "client name cannot be null"
        );

        return Optional.ofNullable(
            reports.get(
                new StatusKey(
                    incidentId,
                    clientName
                )
            )
        );
    }

    private record StatusKey(
        String incidentId,
        String clientName
    ) {
    }
}