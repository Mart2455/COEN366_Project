package com.cenrs.client.files;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

public final class AttachmentFiles {
    private AttachmentFiles() {
    }

    public static String requireSafeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException(
                "file name cannot be blank"
            );
        }

        if (
            fileName.equals(".")
            || fileName.equals("..")
            || fileName.contains("/")
            || fileName.contains("\\")
        ) {
            throw new IllegalArgumentException(
                "file name must not contain a path"
            );
        }

        try {
            Path candidate = Path.of(fileName);

            if (
                candidate.isAbsolute()
                || candidate.getNameCount() != 1
            ) {
                throw new IllegalArgumentException(
                    "file name must not contain a path"
                );
            }
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException(
                "file name is invalid",
                exception
            );
        }

        return fileName;
    }

    public static Path resolve(Path directory, String fileName) {
        Objects.requireNonNull(directory);
        String safeName = requireSafeFileName(fileName);
        Path root = directory.toAbsolutePath().normalize();
        Path resolved = root.resolve(safeName).normalize();

        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                "file path leaves the attachment directory"
            );
        }

        return resolved;
    }
}