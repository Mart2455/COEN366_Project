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

    // Clients restored after a restart that have not registered again yet.
    private final Set<String> inactiveClients =
            ConcurrentHashMap.newKeySet();

    private final Object registrationLock = new Object();

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
    public boolean addInactive(ClientProfile client) {
        Objects.requireNonNull(client);

        synchronized (registrationLock) {
            if (clients.putIfAbsent(client.name(), client) != null) {
                return false;
            }

            inactiveClients.add(client.name());
            return true;
        }
    }

    @Override
    public boolean reactivate(ClientProfile client) {
        Objects.requireNonNull(client);

        // Check and update together, so two registrations cannot both win.
        synchronized (registrationLock) {
            if (!inactiveClients.contains(client.name())) {
                return false;
            }

            clients.put(client.name(), client);
            inactiveClients.remove(client.name());
            return true;
        }
    }

    @Override
    public boolean isActive(String name) {
        return clients.containsKey(name)
                && !inactiveClients.contains(name);
    }

    @Override
    public boolean remove(String name) {
        synchronized (registrationLock) {
            inactiveClients.remove(name);
            return clients.remove(name) != null;
        }
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