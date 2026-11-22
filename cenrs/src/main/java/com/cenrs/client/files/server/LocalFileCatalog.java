package com.cenrs.client.files.server;

import com.cenrs.client.files.AttachmentFiles;
import com.cenrs.contract.port.FileCatalogPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class LocalFileCatalog implements FileCatalogPort {
    private final Map<FileKey, Path> files = new ConcurrentHashMap<>();

    @Override
    public void add(
        String publisherName,
        String incidentId,
        String fileName,
        Path path
    ) {
        FileKey key = new FileKey(
            publisherName,
            incidentId,
            fileName
        );
        Path storedPath = Objects.requireNonNull(path)
            .toAbsolutePath()
            .normalize();

        if (!Files.isRegularFile(storedPath)) {
            throw new IllegalArgumentException(
                "attachment must be an existing regular file"
            );
        }

        files.put(key, storedPath);
    }

    @Override
    public Optional<Path> find(
        String publisherName,
        String incidentId,
        String fileName
    ) {
        try {
            FileKey key = new FileKey(
                publisherName,
                incidentId,
                fileName
            );
            Path path = files.get(key);

            return path != null && Files.isRegularFile(path)
                ? Optional.of(path)
                : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void removeIncident(String incidentId) {
        requireText(incidentId, "incident ID");
        files.keySet().removeIf(
            key -> key.incidentId().equals(incidentId)
        );
    }

    private record FileKey(
        String publisherName,
        String incidentId,
        String fileName
    ) {
        private FileKey {
            publisherName = requireText(
                publisherName,
                "publisher name"
            );
            incidentId = requireText(incidentId, "incident ID");
            fileName = AttachmentFiles.requireSafeFileName(fileName);
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                label + " cannot be blank"
            );
        }

        return value;
    }
}