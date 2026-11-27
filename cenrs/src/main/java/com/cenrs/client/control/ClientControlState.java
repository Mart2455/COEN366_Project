package com.cenrs.client.control;

import com.cenrs.client.files.download.AttachmentInfo;
import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.Incident;
import com.cenrs.contract.StatusReport;
import com.cenrs.contract.Subscription;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

// Thread-safe local state changed by successful client commands.
public final class ClientControlState {
    private ClientProfile profile;
    private List<CampusLocation> availableLocations = List.of();
    private List<AlertCategory> availableCategories = List.of();
    private final Set<Subscription> subscriptions = new HashSet<>();
    private final Map<String, Incident> incidents = new HashMap<>();
    private final Map<String, StatusReport> statusReports = new HashMap<>();
    private final Map<String, AttachmentInfo> attachmentInfos = new HashMap<>();

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

    public synchronized Map<String, Incident> incidents() {
        return Map.copyOf(incidents);
    }

    public synchronized Optional<Incident> incident(String incidentId) {
        return Optional.ofNullable(incidents.get(incidentId));
    }

    public synchronized Map<String, StatusReport> statusReports() {
        return Map.copyOf(statusReports);
    }

    public synchronized Optional<StatusReport> statusReport(
            String incidentId
    ) {
        return Optional.ofNullable(statusReports.get(incidentId));
    }

    public synchronized Map<String, AttachmentInfo> attachmentInfos() {
        return Map.copyOf(attachmentInfos);
    }

    public synchronized Optional<AttachmentInfo> attachmentInfo(
            String incidentId
    ) {
        return Optional.ofNullable(attachmentInfos.get(incidentId));
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
        incidents.clear();
        statusReports.clear();
        attachmentInfos.clear();
    }

    public synchronized void clearRegistration() {
        profile = null;
        availableLocations = List.of();
        availableCategories = List.of();
        subscriptions.clear();
        incidents.clear();
        statusReports.clear();
        attachmentInfos.clear();
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

    public synchronized void saveIncident(Incident incident) {
        Incident requiredIncident = Objects.requireNonNull(incident);
        incidents.put(requiredIncident.incidentId(), requiredIncident);
    }

    public synchronized void saveStatusReport(StatusReport report) {
        StatusReport requiredReport = Objects.requireNonNull(report);
        statusReports.put(requiredReport.incidentId(), requiredReport);
    }

    public synchronized void saveAttachmentInfo(AttachmentInfo info) {
        AttachmentInfo requiredInfo = Objects.requireNonNull(info);
        attachmentInfos.put(requiredInfo.incidentId(), requiredInfo);
    }

    synchronized Incident requireIncident(String incidentId) {
        Incident incident = incidents.get(Objects.requireNonNull(incidentId));

        if (incident == null) {
            throw new IllegalStateException("Unknown incident: " + incidentId);
        }

        return incident;
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
