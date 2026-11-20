package com.cenrs.contract.fake;

import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.Incident;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.Subscription;
import com.cenrs.contract.port.DeliverySchedulerPort;
import com.cenrs.contract.port.FileCatalogPort;
import com.cenrs.contract.port.IncidentStorePort;
import com.cenrs.contract.port.PersistencePort;
import com.cenrs.contract.port.RegistryPort;
import com.cenrs.contract.port.SubscriptionPort;
import com.cenrs.contract.port.TransportPort;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FakePorts {
    private FakePorts() {
    }

    public static final class Registry implements RegistryPort {
        private final Map<String, ClientProfile> clients =
            new ConcurrentHashMap<>();

        @Override
        public boolean addIfAbsent(ClientProfile client) {
            Objects.requireNonNull(client);
            return clients.putIfAbsent(client.name(), client) == null;
        }

        @Override
        public Optional<ClientProfile> findByName(String name) {
            return Optional.ofNullable(clients.get(name));
        }

        @Override
        public List<ClientProfile> findAll() {
            return List.copyOf(clients.values());
        }

        @Override
        public boolean remove(String name) {
            return clients.remove(name) != null;
        }
    }

    public static final class Subscriptions implements SubscriptionPort {
        private final Map<String, Set<Subscription>> subscriptions =
            new ConcurrentHashMap<>();

        @Override
        public boolean add(
            String clientName,
            Subscription subscription
        ) {
            return subscriptions
                .computeIfAbsent(
                    clientName,
                    key -> ConcurrentHashMap.newKeySet()
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

            return clientSubscriptions != null
                && clientSubscriptions.remove(subscription);
        }

        @Override
        public Set<Subscription> findByClient(String clientName) {
            Set<Subscription> clientSubscriptions =
                subscriptions.get(clientName);

            return clientSubscriptions == null
                ? Set.of()
                : Set.copyOf(clientSubscriptions);
        }
    }

    public static final class Incidents implements IncidentStorePort {
        private final Map<String, Incident> incidents =
            new ConcurrentHashMap<>();

        @Override
        public boolean create(Incident incident) {
            return incidents.putIfAbsent(
                incident.incidentId(),
                incident
            ) == null;
        }

        @Override
        public Optional<Incident> findById(String incidentId) {
            return Optional.ofNullable(incidents.get(incidentId));
        }

        @Override
        public List<Incident> findActive() {
            return incidents.values()
                .stream()
                .filter(
                    incident ->
                        incident.status() == IncidentStatus.ACTIVE
                )
                .toList();
        }

        @Override
        public boolean replaceIfCurrent(
            Incident current,
            Incident updated
        ) {
            if (!current.incidentId().equals(updated.incidentId())) {
                return false;
            }

            return incidents.replace(
                current.incidentId(),
                current,
                updated
            );
        }

        private final Map<String, Set<String>> recipients = new ConcurrentHashMap<>();

        @Override 
        public void recordRecipients(String incidentId , Set<String> clientNames)
        {
            // Verify that the incident actually exists
            if (!incidents.containsKey(incidentId))
            {
                throw new IllegalArgumentException("Unkown incident.");
            }

            // Record the recipients for the incident
            Set<String> names = Set.copyOf(clientNames);

            // Keep all previously notified clients and add new recipients without duplicates,
            // so ALL_CLEAR can reach them even if they later unsubscribe.
            recipients.compute(incidentId, (id, previous) -> {
                Set<String> combined = new java.util.HashSet<>(
                        previous == null ? Set.of() : previous
                );
                combined.addAll(names);
                return Set.copyOf(combined);
            });

        }

        @Override 
        public Set<String> findRecipients(String incidentId) 
        {
            return recipients.getOrDefault(incidentId, Set.of());
        }
    }

    public static final class Scheduler
        implements DeliverySchedulerPort {

        private final List<ScheduledTask> tasks =
            new CopyOnWriteArrayList<>();

        @Override
        public ScheduledDelivery schedule(
            Duration delay,
            Runnable task
        ) {
            Objects.requireNonNull(delay);
            Objects.requireNonNull(task);

            if (delay.isNegative()) {
                throw new IllegalArgumentException(
                    "delay cannot be negative"
                );
            }

            ScheduledTask scheduledTask = new ScheduledTask(task);
            tasks.add(scheduledTask);
            return scheduledTask;
        }

        public void runPending() {
            tasks.forEach(ScheduledTask::run);
        }

        private static final class ScheduledTask
            implements ScheduledDelivery {

            private final Runnable task;
            private final AtomicBoolean cancelled =
                new AtomicBoolean();
            private final AtomicBoolean completed =
                new AtomicBoolean();

            private ScheduledTask(Runnable task) {
                this.task = task;
            }

            private void run() {
                if (
                    !cancelled.get()
                    && completed.compareAndSet(false, true)
                ) {
                    task.run();
                }
            }

            @Override
            public boolean cancel() {
                return cancelled.compareAndSet(false, true);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        }
    }

    public static final class Transport implements TransportPort {
        private final List<SentMessage> messages =
            new CopyOnWriteArrayList<>();

        @Override
        public void send(
            InetSocketAddress destination,
            byte[] message
        ) throws IOException {
            messages.add(new SentMessage(destination, message));
        }

        public List<SentMessage> sentMessages() {
            return List.copyOf(messages);
        }

        public record SentMessage(
            InetSocketAddress destination,
            byte[] message
        ) {
            public SentMessage {
                Objects.requireNonNull(destination);
                message = message.clone();
            }

            @Override
            public byte[] message() {
                return message.clone();
            }
        }
    }

    public static final class Persistence
        implements PersistencePort {

        private byte[] snapshot;

        @Override
        public synchronized Optional<byte[]> load() {
            return snapshot == null
                ? Optional.empty()
                : Optional.of(snapshot.clone());
        }

        @Override
        public synchronized void save(byte[] snapshot) {
            this.snapshot = snapshot.clone();
        }
    }

    public static final class FileCatalog
        implements FileCatalogPort {

        private final Map<FileKey, Path> files =
            new ConcurrentHashMap<>();

        @Override
        public void add(
            String publisherName,
            String incidentId,
            String fileName,
            Path path
        ) {
            files.put(
                new FileKey(
                    publisherName,
                    incidentId,
                    fileName
                ),
                path
            );
        }

        @Override
        public Optional<Path> find(
            String publisherName,
            String incidentId,
            String fileName
        ) {
            return Optional.ofNullable(
                files.get(
                    new FileKey(
                        publisherName,
                        incidentId,
                        fileName
                    )
                )
            );
        }

        @Override
        public void removeIncident(String incidentId) {
            files.keySet().removeIf(
                key -> key.incidentId().equals(incidentId)
            );
        }

        private record FileKey(
            String publisherName,
            String incidentId,
            String fileName
        ) {
        }
    }
}