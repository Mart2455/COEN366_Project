package com.cenrs.server.persistence;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.Subscription;
import com.cenrs.contract.port.PersistencePort;
import com.cenrs.contract.port.RegistryPort;
import com.cenrs.contract.port.SubscriptionPort;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ServerStateStore {
    private static final String HEADER =
            "CENRS-STATE|1";

    private final RegistryPort registry;
    private final SubscriptionPort subscriptions;
    private final PersistencePort persistence;

    public ServerStateStore(
            RegistryPort registry,
            SubscriptionPort subscriptions,
            PersistencePort persistence
    ) {
        this.registry = Objects.requireNonNull(registry);
        this.subscriptions =
                Objects.requireNonNull(subscriptions);
        this.persistence =
                Objects.requireNonNull(persistence);
    }

    public synchronized void save()
            throws IOException {

        List<String> lines = new ArrayList<>();
        lines.add(HEADER);

        // Stable ordering makes the saved file predictable and readable.
        List<ClientProfile> clients =
                registry.findAll()
                        .stream()
                        .sorted(
                                Comparator.comparing(
                                        ClientProfile::name
                                )
                        )
                        .toList();

        for (ClientProfile client : clients) {
            lines.add(
                    String.join(
                            "|",
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
                                            (
                                                    Subscription subscription
                                            ) ->
                                                    subscription
                                                            .location()
                                                            .name()
                                    )
                                    .thenComparing(
                                            subscription ->
                                                    subscription
                                                            .category()
                                                            .name()
                                    )
                    )
                    .forEach(
                            subscription ->
                                    lines.add(
                                            String.join(
                                                    "|",
                                                    "SUBSCRIPTION",
                                                    client.name(),
                                                    subscription
                                                            .location()
                                                            .name(),
                                                    subscription
                                                            .category()
                                                            .name()
                                            )
                                    )
                    );
        }

        String snapshot =
                String.join("\n", lines) + "\n";

        persistence.save(
                snapshot.getBytes(StandardCharsets.UTF_8)
        );
    }

    public synchronized void restore()
            throws IOException {

        Optional<byte[]> savedState =
                persistence.load();

        if (savedState.isEmpty()) {
            return;
        }

        String snapshot =
                decodeUtf8(savedState.get());

        String[] lines = snapshot.split("\n", -1);

        if (
                lines.length == 0
                || !HEADER.equals(lines[0])
        ) {
            throw new IOException(
                    "Unsupported or invalid state snapshot"
            );
        }

        List<ClientProfile> restoredClients =
                new ArrayList<>();

        Map<String, Set<Subscription>>
                restoredSubscriptions =
                new HashMap<>();

        Set<String> clientNames = new HashSet<>();

        // Read and validate everything before changing the live server state.
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];

            if (line.isEmpty()) {
                continue;
            }

            String[] fields = line.split("\\|", -1);
            int lineNumber = index + 1;

            switch (fields[0]) {
                case "CLIENT" -> {
                    ClientProfile client =
                            parseClient(
                                    fields,
                                    lineNumber
                            );

                    if (!clientNames.add(client.name())) {
                        throw new IOException(
                                "Duplicate client on line "
                                        + lineNumber
                        );
                    }

                    restoredClients.add(client);
                }

                case "SUBSCRIPTION" -> {
                    SubscriptionEntry entry =
                            parseSubscription(
                                    fields,
                                    lineNumber
                            );

                    restoredSubscriptions
                            .computeIfAbsent(
                                    entry.clientName(),
                                    ignored -> new HashSet<>()
                            )
                            .add(entry.subscription());
                }

                default -> throw new IOException(
                        "Unknown state record on line "
                                + lineNumber
                );
            }
        }

        for (String clientName : restoredSubscriptions.keySet()) {
            if (!clientNames.contains(clientName)) {
                throw new IOException(
                        "Subscription belongs to unknown client "
                                + clientName
                );
            }
        }

        // Prevent a restore from silently replacing different live data.
        for (ClientProfile client : restoredClients) {
            Optional<ClientProfile> existing =
                    registry.findByName(client.name());

            if (
                    existing.isPresent()
                    && !existing.get().equals(client)
            ) {
                throw new IOException(
                        "State conflicts with existing client "
                                + client.name()
                );
            }
        }

        for (ClientProfile client : restoredClients) {
            registry.addIfAbsent(client);
        }

        for (
                Map.Entry<String, Set<Subscription>> entry
                : restoredSubscriptions.entrySet()
        ) {
            for (
                    Subscription subscription
                    : entry.getValue()
            ) {
                subscriptions.add(
                        entry.getKey(),
                        subscription
                );
            }
        }
    }

    private ClientProfile parseClient(
            String[] fields,
            int lineNumber
    ) throws IOException {

        if (fields.length != 6) {
            throw new IOException(
                    "Invalid client record on line "
                            + lineNumber
            );
        }

        try {
            return new ClientProfile(
                    fields[1],
                    ClientRole.valueOf(fields[2]),
                    fields[3],
                    Integer.parseInt(fields[4]),
                    Integer.parseInt(fields[5])
            );
        } catch (RuntimeException exception) {
            throw new IOException(
                    "Invalid client record on line "
                            + lineNumber,
                    exception
            );
        }
    }

    private SubscriptionEntry parseSubscription(
            String[] fields,
            int lineNumber
    ) throws IOException {

        if (fields.length != 4) {
            throw new IOException(
                    "Invalid subscription record on line "
                            + lineNumber
            );
        }

        try {
            return new SubscriptionEntry(
                    fields[1],
                    new Subscription(
                            CampusLocation.valueOf(fields[2]),
                            AlertCategory.valueOf(fields[3])
                    )
            );
        } catch (RuntimeException exception) {
            throw new IOException(
                    "Invalid subscription record on line "
                            + lineNumber,
                    exception
            );
        }
    }

    private String decodeUtf8(byte[] bytes)
            throws IOException {

        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                            CodingErrorAction.REPORT
                    )
                    .onUnmappableCharacter(
                            CodingErrorAction.REPORT
                    )
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException(
                    "State snapshot is not valid UTF-8",
                    exception
            );
        }
    }

    private record SubscriptionEntry(
            String clientName,
            Subscription subscription
    ) {
    }
}