package com.cenrs.contract.port;

import java.io.IOException;
import java.util.Optional;

public interface PersistencePort {
    Optional<byte[]> load() throws IOException;

    void save(byte[] snapshot) throws IOException;
}