package com.cenrs.server.persistence;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.Incident;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.SafetyStatus;
import com.cenrs.contract.Severity;
import com.cenrs.contract.StatusReport;
import com.cenrs.contract.Subscription;
import com.cenrs.contract.port.PersistencePort;
import com.cenrs.contract.port.RegistryPort;
import com.cenrs.contract.port.SubscriptionPort;
import com.cenrs.server.delivery.DeliveryKey;
import com.cenrs.server.delivery.DeliveryState;
import com.cenrs.server.delivery.InMemoryDeliveryStateRepository;
import com.cenrs.server.incident.InMemoryIncidentStore;
import com.cenrs.server.incident.SequentialIncidentIdGenerator;
import com.cenrs.server.status.InMemoryStatusStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Saves the whole server state to one text snapshot and restores it after a crash.
 *
 * One record per line, fields separated by "|":
 *   CLIENT|Name|Role|IP|UDP_Port|TCP_Port
 *   SUBSCRIPTION|Name|Location|Category
 *   NEXT_INCIDENT|Number
 *   INCIDENT|ID|Version|Category|Location|Severity|Status|Title|Description|Attachment|Publisher
 *   RECIPIENT|Incident_ID|Name
 *   STATUS|Incident_ID|Name|Safety_Status
 *   DELIVERY|RQ#|Incident_ID|Version|Name|Delivery_Status|Attempts|Updated_Epoch_Millis
 *
 * The file is written by FilePersistence (temporary file + atomic replace).
 */
public final class ServerStateStore {

    private static final String HEADER_V1 = "CENRS-STATE|1";
    private static final String HEADER = "CENRS-STATE|2";

    private final RegistryPort registry;
    private final SubscriptionPort subscriptions;
    private final InMemoryIncidentStore incidents;
    private final SequentialIncidentIdGenerator incidentIds;
    private final InMemoryStatusStore statuses;
    private final InMemoryDeliveryStateRepository deliveries;
    private final PersistencePort persistence;

    /** Phase One constructor: only clients and subscriptions are shared with the server. */
    public ServerStateStore(
            RegistryPort registry,
            SubscriptionPort subscriptions,
            PersistencePort persistence
    ) {
        this(
                registry,
                subscriptions,
                new InMemoryIncidentStore(),
                new SequentialIncidentIdGenerator(),
                new InMemoryStatusStore(),
                new InMemoryDeliveryStateRepository(),
                persistence
        );
    }

    public ServerStateStore(
            RegistryPort registry,
            SubscriptionPort subscriptions,
            InMemoryIncidentStore incidents,
            SequentialIncidentIdGenerator incidentIds,
            InMemoryStatusStore statuses,
            InMemoryDeliveryStateRepository deliveries,
            PersistencePort persistence
    ) {
        this.registry = Objects.requireNonNull(registry);
        this.subscriptions = Objects.requireNonNull(subscriptions);
        this.incidents = Objects.requireNonNull(incidents);
        this.incidentIds = Objects.requireNonNull(incidentIds);
        this.statuses = Objects.requireNonNull(statuses);
        this.deliveries = Objects.requireNonNull(deliveries);
        this.persistence = Objects.requireNonNull(persistence);
    }

    // ------------------------------------------------------------------ save

    public synchronized void save()
            throws IOException {

        List<String> lines = new ArrayList<>();
        lines.add(HEADER);

        saveClientsAndSubscriptions(lines);

        // Saved so incident IDs are never reused after a restart.
        lines.add(
                record(
                        "NEXT_INCIDENT",
                        Long.toString(incidentIds.peekNextNumber())
                )
        );

        for (Incident incident : incidents.findAll()) {
            lines.add(
                    record(
                            "INCIDENT",
                            incident.incidentId(),
                            Long.toString(incident.version()),
                            incident.category().name(),
                            incident.location().name(),
                            incident.severity().name(),
                            incident.status().name(),
                            incident.title(),
                            incident.description(),
                            incident.attachment(),
                            incident.publisherName()
                    )
            );

            // Sorted so the file is predictable and readable.
            for (
                    String name
                    : new TreeSet<>(
                            incidents.findRecipients(incident.incidentId())
                    )
            ) {
                lines.add(
                        record(
                                "RECIPIENT",
                                incident.incidentId(),
                                name
                        )
                );
            }
        }

        for (StatusReport report : statuses.findAll()) {
            lines.add(
                    record(
                            "STATUS",
                            report.incidentId(),
                            report.clientName(),
                            report.status().name()
                    )
            );
        }

        deliveries.findAll()
                .stream()
                .sorted(
                        Comparator
                                .comparing(
                                        (DeliveryState state) ->
                                                state.key().incidentId()
                                )
                                .thenComparingLong(
                                        state -> state.key().version()
                                )
                                .thenComparing(
                                        state -> state.key().clientName()
                                )
                                .thenComparingLong(
                                        state -> state.key().requestNumber()
                                )
                )
                .forEach(
                        state -> lines.add(
                                record(
                                        "DELIVERY",
                                        Long.toString(
                                                state.key().requestNumber()
                                        ),
                                        state.key().incidentId(),
                                        Long.toString(state.key().version()),
                                        state.key().clientName(),
                                        state.status().name(),
                                        Integer.toString(state.attempts()),
                                        Long.toString(
                                                state.updatedAt().toEpochMilli()
                                        )
                                )
                        )
                );

        String snapshot = String.join("\n", lines) + "\n";

        persistence.save(
                snapshot.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void saveClientsAndSubscriptions(List<String> lines) {
        // Stable ordering makes the saved file predictable and readable.
        List<ClientProfile> clients =
                registry.findAll()
                        .stream()
                        .sorted(Comparator.comparing(ClientProfile::name))
                        .toList();

        for (ClientProfile client : clients) {
            lines.add(
                    record(
                            "CLIENT",
                            client.name(),
                            client.role().name(),
                            client.ipAddress(),
                            Integer.toString(client.udpPort()),
                            Integer.toString(client.tcpPort())
                    )
            );

            subscriptions
                    .findByClient(client.name())
                    .stream()
                    .sorted(
                            Comparator
                                    .comparing(
                                            (Subscription subscription) ->
                                                    subscription.location().name()
                                    )
                                    .thenComparing(
                                            subscription ->
                                                    subscription.category().name()
                                    )
                    )
                    .forEach(
                            subscription -> lines.add(
                                    record(
                                            "SUBSCRIPTION",
                                            client.name(),
                                            subscription.location().name(),
                                            subscription.category().name()
                                    )
                            )
                    );
        }
    }

    // --------------------------------------------------------------- restore

    public synchronized void restore()
            throws IOException {

        Optional<byte[]> savedState = persistence.load();

        if (savedState.isEmpty()) {
            return;
        }

        String snapshot = decodeUtf8(savedState.get());
        String[] lines = snapshot.split("\n", -1);

        if (
                lines.length == 0
                || !(HEADER.equals(lines[0]) || HEADER_V1.equals(lines[0]))
        ) {
            throw new IOException("Unsupported or invalid state snapshot");
        }

        Snapshot parsed = parse(lines);

        validate(parsed);

        apply(parsed);
    }

    /** Reads every line first. Nothing in the live server changes yet. */
    private Snapshot parse(String[] lines)
            throws IOException {

        Snapshot parsed = new Snapshot();
        Set<String> clientNames = new HashSet<>();
        Set<String> incidentIdsSeen = new HashSet<>();

        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];

            if (line.isEmpty()) {
                continue;
            }

            String[] fields = line.split("\\|", -1);
            int lineNumber = index + 1;

            switch (fields[0]) {
                case "CLIENT" -> {
                    ClientProfile client = parseClient(fields, lineNumber);

                    if (!clientNames.add(client.name())) {
                        throw new IOException(
                                "Duplicate client on line " + lineNumber
                        );
                    }

                    parsed.clients.add(client);
                }

                case "SUBSCRIPTION" -> {
                    SubscriptionEntry entry =
                            parseSubscription(fields, lineNumber);

                    parsed.subscriptions
                            .computeIfAbsent(
                                    entry.clientName(),
                                    ignored -> new HashSet<>()
                            )
                            .add(entry.subscription());
                }

                case "NEXT_INCIDENT" -> {
                    expectFields(fields, 2, "next incident", lineNumber);
                    parsed.nextIncidentNumber =
                            parsePositiveLong(fields[1], "next incident", lineNumber);
                }

                case "INCIDENT" -> {
                    Incident incident = parseIncident(fields, lineNumber);

                    if (!incidentIdsSeen.add(incident.incidentId())) {
                        throw new IOException(
                                "Duplicate incident on line " + lineNumber
                        );
                    }

                    parsed.incidents.add(incident);
                }

                case "RECIPIENT" -> {
                    expectFields(fields, 3, "recipient", lineNumber);
                    requireText(fields[1], "recipient", lineNumber);
                    requireText(fields[2], "recipient", lineNumber);

                    parsed.recipients
                            .computeIfAbsent(
                                    fields[1],
                                    ignored -> new HashSet<>()
                            )
                            .add(fields[2]);
                }

                case "STATUS" ->
                        parsed.statuses.add(parseStatus(fields, lineNumber));

                case "DELIVERY" ->
                        parsed.deliveries.add(parseDelivery(fields, lineNumber));

                default -> throw new IOException(
                        "Unknown state record on line " + lineNumber
                );
            }
        }

        return parsed;
    }

    /** Checks that records point to things that exist, and do not conflict with live data. */
    private void validate(Snapshot parsed)
            throws IOException {

        Set<String> clientNames = new HashSet<>();
        parsed.clients.forEach(client -> clientNames.add(client.name()));

        for (String clientName : parsed.subscriptions.keySet()) {
            if (!clientNames.contains(clientName)) {
                throw new IOException(
                        "Subscription belongs to unknown client " + clientName
                );
            }
        }

        Set<String> incidentIds = new HashSet<>();
        parsed.incidents.forEach(incident -> incidentIds.add(incident.incidentId()));

        for (String incidentId : parsed.recipients.keySet()) {
            if (!incidentIds.contains(incidentId)) {
                throw new IOException(
                        "Recipient belongs to unknown incident " + incidentId
                );
            }
        }

        for (StatusReport report : parsed.statuses) {
            if (!incidentIds.contains(report.incidentId())) {
                throw new IOException(
                        "Status belongs to unknown incident " + report.incidentId()
                );
            }
        }

        // Prevent a restore from silently replacing different live data.
        for (ClientProfile client : parsed.clients) {
            Optional<ClientProfile> existing = registry.findByName(client.name());

            if (existing.isPresent() && !existing.get().equals(client)) {
                throw new IOException(
                        "State conflicts with existing client " + client.name()
                );
            }
        }

        for (Incident incident : parsed.incidents) {
            Optional<Incident> existing = incidents.findById(incident.incidentId());

            if (existing.isPresent() && !existing.get().equals(incident)) {
                throw new IOException(
                        "State conflicts with existing incident "
                                + incident.incidentId()
                );
            }
        }
    }

    /** Everything was valid: now copy it into the live server state. */
    private void apply(Snapshot parsed) {

        // Restored clients stay inactive until they register again.
        for (ClientProfile client : parsed.clients) {
            registry.addInactive(client);
        }

        parsed.subscriptions.forEach(
                (clientName, clientSubscriptions) ->
                        clientSubscriptions.forEach(
                                subscription ->
                                        subscriptions.add(clientName, subscription)
                        )
        );

        long highestIncidentNumber = 0;

        for (Incident incident : parsed.incidents) {
            incidents.create(incident);

            highestIncidentNumber = Math.max(
                    highestIncidentNumber,
                    SequentialIncidentIdGenerator.numberOf(incident.incidentId())
            );
        }

        parsed.recipients.forEach(incidents::recordRecipients);

        parsed.statuses.forEach(statuses::saveLatest);

        // Retransmission timers do not survive a crash, so a delivery that was
        // still PENDING can no longer be retried: it is restored as UNCONFIRMED.
        for (DeliveryState state : parsed.deliveries) {
            deliveries.save(
                    state.status() == DeliveryState.Status.PENDING
                            ? new DeliveryState(
                                    state.key(),
                                    DeliveryState.Status.UNCONFIRMED,
                                    state.attempts(),
                                    state.updatedAt()
                            )
                            : state
            );
        }

        // Never reuse an ID, even if NEXT_INCIDENT was missing or too low.
        incidentIds.advanceTo(
                Math.max(parsed.nextIncidentNumber, highestIncidentNumber + 1)
        );
    }

    // ------------------------------------------------------------- parsers

    private ClientProfile parseClient(String[] fields, int lineNumber)
            throws IOException {

        expectFields(fields, 6, "client", lineNumber);

        try {
            return new ClientProfile(
                    fields[1],
                    ClientRole.valueOf(fields[2]),
                    fields[3],
                    Integer.parseInt(fields[4]),
                    Integer.parseInt(fields[5])
            );
        } catch (RuntimeException exception) {
            throw invalid("client", lineNumber, exception);
        }
    }

    private SubscriptionEntry parseSubscription(String[] fields, int lineNumber)
            throws IOException {

        expectFields(fields, 4, "subscription", lineNumber);

        try {
            return new SubscriptionEntry(
                    fields[1],
                    new Subscription(
                            CampusLocation.valueOf(fields[2]),
                            AlertCategory.valueOf(fields[3])
                    )
            );
        } catch (RuntimeException exception) {
            throw invalid("subscription", lineNumber, exception);
        }
    }

    private Incident parseIncident(String[] fields, int lineNumber)
            throws IOException {

        expectFields(fields, 11, "incident", lineNumber);

        try {
            return new Incident(
                    fields[1],
                    Long.parseLong(fields[2]),
                    AlertCategory.valueOf(fields[3]),
                    CampusLocation.valueOf(fields[4]),
                    Severity.valueOf(fields[5]),
                    IncidentStatus.valueOf(fields[6]),
                    fields[7],
                    fields[8],
                    fields[9],
                    fields[10]
            );
        } catch (RuntimeException exception) {
            throw invalid("incident", lineNumber, exception);
        }
    }

    private StatusReport parseStatus(String[] fields, int lineNumber)
            throws IOException {

        expectFields(fields, 4, "status", lineNumber);

        try {
            return new StatusReport(
                    fields[1],
                    fields[2],
                    SafetyStatus.valueOf(fields[3])
            );
        } catch (RuntimeException exception) {
            throw invalid("status", lineNumber, exception);
        }
    }

    private DeliveryState parseDelivery(String[] fields, int lineNumber)
            throws IOException {

        expectFields(fields, 8, "delivery", lineNumber);

        try {
            return new DeliveryState(
                    new DeliveryKey(
                            Long.parseLong(fields[1]),
                            fields[2],
                            Long.parseLong(fields[3]),
                            fields[4]
                    ),
                    DeliveryState.Status.valueOf(fields[5]),
                    Integer.parseInt(fields[6]),
                    Instant.ofEpochMilli(Long.parseLong(fields[7]))
            );
        } catch (RuntimeException exception) {
            throw invalid("delivery", lineNumber, exception);
        }
    }

    // ------------------------------------------------------------- helpers

    private static String record(String... fields) {
        return String.join("|", fields);
    }

    private static void expectFields(
            String[] fields,
            int count,
            String recordName,
            int lineNumber
    ) throws IOException {

        if (fields.length != count) {
            throw new IOException(
                    "Invalid " + recordName + " record on line " + lineNumber
            );
        }
    }

    private static void requireText(
            String value,
            String recordName,
            int lineNumber
    ) throws IOException {

        if (value.isBlank()) {
            throw new IOException(
                    "Invalid " + recordName + " record on line " + lineNumber
            );
        }
    }

    private static long parsePositiveLong(
            String value,
            String recordName,
            int lineNumber
    ) throws IOException {

        try {
            long number = Long.parseLong(value);

            if (number < 1) {
                throw new NumberFormatException("not positive");
            }

            return number;
        } catch (NumberFormatException exception) {
            throw invalid(recordName, lineNumber, exception);
        }
    }

    private static IOException invalid(
            String recordName,
            int lineNumber,
            Exception cause
    ) {
        return new IOException(
                "Invalid " + recordName + " record on line " + lineNumber,
                cause
        );
    }

    private String decodeUtf8(byte[] bytes)
            throws IOException {

        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("State snapshot is not valid UTF-8", exception);
        }
    }

    private record SubscriptionEntry(
            String clientName,
            Subscription subscription
    ) {
    }

    /** Everything read from the file, before it is applied. */
    private static final class Snapshot {
        private final List<ClientProfile> clients = new ArrayList<>();
        private final Map<String, Set<Subscription>> subscriptions = new HashMap<>();
        private final List<Incident> incidents = new ArrayList<>();
        private final Map<String, Set<String>> recipients = new HashMap<>();
        private final List<StatusReport> statuses = new ArrayList<>();
        private final List<DeliveryState> deliveries = new ArrayList<>();
        private long nextIncidentNumber = 1;
    }
}
