package com.cenrs.server.delivery;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryDeliveryStateRepository
    implements DeliveryStateRepository {

    private final ConcurrentMap<DeliveryKey, DeliveryState> states =
        new ConcurrentHashMap<>();

    @Override
    public void save(DeliveryState state) {
        Objects.requireNonNull(state, "state cannot be null");
        states.put(state.key(), state);
    }

    @Override
    public Optional<DeliveryState> find(DeliveryKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        return Optional.ofNullable(states.get(key));
    }

    /** Every recorded delivery. Used for persistence. */
    public List<DeliveryState> findAll() {
        return List.copyOf(states.values());
    }
}
