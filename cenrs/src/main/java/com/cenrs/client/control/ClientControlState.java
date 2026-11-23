package com.cenrs.client.control;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.Subscription;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

//Thread-safe local state changed by successful client commands.

public final class ClientControlState {
    private ClientProfile profile;
    private List<CampusLocation> availableLocations = List.of();
    private List<AlertCategory> availableCategories = List.of();
    private final Set<Subscription> subscriptions = new HashSet<>();

    public synchronized Optional<ClientProfile> profile() {
        return Optional.ofNullable(profile);
    }

    public synchronized List<CampusLocation> availableLocations() {
        return List.copyOf(availableLocations);
    }

    public synchronized List<AlertCategory> availableCategories() {
        return List.copyOf(availableCategories);
    }

    public synchronized Set<Subscription> subscriptions() {
        return Set.copyOf(subscriptions);
    }

    public synchronized void recordRegistration(
            ClientProfile registeredProfile,
            List<CampusLocation> locations,
            List<AlertCategory> categories
    ) {
        profile = Objects.requireNonNull(registeredProfile);
        availableLocations = List.copyOf(locations);
        availableCategories = List.copyOf(categories);
        subscriptions.clear();
    }

    public synchronized void clearRegistration() {
        profile = null;
        availableLocations = List.of();
        availableCategories = List.of();
        subscriptions.clear();
    }

    public synchronized void addSubscription(
            Subscription subscription
    ) {
        subscriptions.add(Objects.requireNonNull(subscription));
    }

    public synchronized void removeSubscription(
            Subscription subscription
    ) {
        subscriptions.remove(Objects.requireNonNull(subscription));
    }

    synchronized ClientProfile requireProfile() {
        if (profile == null) {
            throw new IllegalStateException(
                    "Client is not registered"
            );
        }

        return profile;
    }
}
