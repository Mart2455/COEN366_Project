package com.cenrs.server.status;

import com.cenrs.contract.StatusReport;
import com.cenrs.contract.port.StatusStorePort;

import java.util.Comparator;
import java.util.List;
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

    /** Latest report of every client for every incident. Used for persistence. */
    public List<StatusReport> findAll() {
        return reports.values()
            .stream()
            .sorted(
                Comparator.comparing(StatusReport::incidentId)
                    .thenComparing(StatusReport::clientName)
            )
            .toList();
    }

    private record StatusKey(
        String incidentId,
        String clientName
    ) {
    }
}