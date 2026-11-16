package com.cenrs.server.state;

import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.Subscription;
import com.cenrs.contract.port.RegistryPort;
import com.cenrs.contract.port.SubscriptionPort;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerState
        implements RegistryPort, SubscriptionPort {

    private final Map<String, ClientProfile> clients =
            new ConcurrentHashMap<>();

    private final Map<String, Set<Subscription>>
            subscriptions =
            new ConcurrentHashMap<>();

    @Override
    public boolean addIfAbsent(ClientProfile client) {
        Objects.requireNonNull(client);

        return clients.putIfAbsent(
                client.name(),
                client
        ) == null;
    }

    @Override
    public Optional<ClientProfile> findByName(
            String name
    ) {
        return Optional.ofNullable(
                clients.get(name)
        );
    }

    @Override
    public List<ClientProfile> findAll() {
        return List.copyOf(clients.values());
    }

    @Override
    public boolean remove(String name) {
        return clients.remove(name) != null;
    }

    @Override
    public boolean add(
            String clientName,
            Subscription subscription
    ) {
        Objects.requireNonNull(clientName);
        Objects.requireNonNull(subscription);

        return subscriptions
                .computeIfAbsent(
                        clientName,
                        ignored ->
                                ConcurrentHashMap.newKeySet()
                )
                .add(subscription);
    }

    @Override
    public boolean remove(
            String clientName,
            Subscription subscription
    ) {
        Set<Subscription> clientSubscriptions =
                subscriptions.get(clientName);

        if (clientSubscriptions == null) {
            return false;
        }

        boolean removed =
                clientSubscriptions.remove(subscription);

        if (clientSubscriptions.isEmpty()) {
            subscriptions.remove(
                    clientName,
                    clientSubscriptions
            );
        }

        return removed;
    }

    @Override
    public Set<Subscription> findByClient(
            String clientName
    ) {
        Set<Subscription> clientSubscriptions =
                subscriptions.get(clientName);

        return clientSubscriptions == null
                ? Set.of()
                : Set.copyOf(clientSubscriptions);
    }
}