package com.cenrs.server.persistence;

import com.cenrs.contract.port.PersistencePort;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

public final class FilePersistence
        implements PersistencePort {

    private final Path snapshotFile;

    public FilePersistence(Path snapshotFile) {
        this.snapshotFile = Objects
                .requireNonNull(snapshotFile)
                .toAbsolutePath();
    }

    @Override
    public synchronized Optional<byte[]> load()
            throws IOException {

        if (!Files.exists(snapshotFile)) {
            return Optional.empty();
        }

        return Optional.of(
                Files.readAllBytes(snapshotFile)
        );
    }

    @Override
    public synchronized void save(byte[] snapshot)
            throws IOException {

        Objects.requireNonNull(snapshot);

        Path directory = snapshotFile.getParent();
        Files.createDirectories(directory);

        Path temporaryFile = Files.createTempFile(
                directory,
                snapshotFile.getFileName().toString(),
                ".tmp"
        );

        try {
            writeCompleteSnapshot(
                    temporaryFile,
                    snapshot
            );

            replaceSnapshot(temporaryFile);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    // The old state remains safe until the new file is completely written.
    private void writeCompleteSnapshot(
            Path temporaryFile,
            byte[] snapshot
    ) throws IOException {

        try (
                FileChannel channel = FileChannel.open(
                        temporaryFile,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                )
        ) {
            ByteBuffer buffer =
                    ByteBuffer.wrap(snapshot);

            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }

            channel.force(true);
        }
    }

    // Replace the old snapshot only after the new snapshot is ready.
    private void replaceSnapshot(
            Path temporaryFile
    ) throws IOException {

        try {
            Files.move(
                    temporaryFile,
                    snapshotFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporaryFile,
                    snapshotFile,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }
}