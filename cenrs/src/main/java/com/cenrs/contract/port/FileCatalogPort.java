package com.cenrs.contract.port;

import java.nio.file.Path;
import java.util.Optional;

public interface FileCatalogPort {
    void add(
        String publisherName,
        String incidentId,
        String fileName,
        Path path
    );

    Optional<Path> find(
        String publisherName,
        String incidentId,
        String fileName
    );

    void removeIncident(String incidentId);
}