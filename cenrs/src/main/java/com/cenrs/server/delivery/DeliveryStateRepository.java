package com.cenrs.server.delivery;

import java.util.Optional;

public interface DeliveryStateRepository {
    void save(DeliveryState state);

    Optional<DeliveryState> find(DeliveryKey key);
}