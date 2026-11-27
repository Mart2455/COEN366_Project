package com.cenrs.client.sync;

import com.cenrs.client.sync.ActiveAlertSyncClient.SyncDeniedException;
import com.cenrs.client.sync.ClientIncidentState.SyncReport;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.ProtocolException;

import java.io.IOException;
import java.util.Objects;

/**
 * How a client resumes after it was disconnected or the server restarted (issue 24):
 *
 *   1. REGISTER again (the server keeps restored clients inactive until they do),
 *   2. GET_ACTIVE_ALERTS over TCP,
 *   3. reconcile the local incident list with the server's list.
 */
public final class ClientReconnector {

    /** The server's reason when a restored client has not registered again. */
    public static final String NOT_REGISTERED_REASON = "Client not registered";

    /** Sends REGISTER again and returns true if the server answered REGISTERED. */
    @FunctionalInterface
    public interface Registration {
        boolean registerAgain() throws Exception;
    }

    private final String clientName;
    private final Registration registration;
    private final ActiveAlertSyncClient syncClient;
    private final ClientIncidentState state;

    public ClientReconnector(
            String clientName,
            Registration registration,
            ActiveAlertSyncClient syncClient,
            ClientIncidentState state
    ) {
        this.clientName = Objects.requireNonNull(clientName);
        this.registration = Objects.requireNonNull(registration);
        this.syncClient = Objects.requireNonNull(syncClient);
        this.state = Objects.requireNonNull(state);
    }

    /** True if a server denial means "register again first" (e.g. after a server restart). */
    public static boolean needsReRegistration(ControlMessage response) {
        if (response == null || response.fields().isEmpty()) {
            return false;
        }

        String lastField = response.field(response.fields().size() - 1);
        return NOT_REGISTERED_REASON.equals(lastField);
    }

    /** Synchronize only (client already registered, e.g. it just started or missed UDP alerts). */
    public SyncReport synchronize()
            throws IOException, ProtocolException, SyncDeniedException {

        SyncReport report = state.reconcile(syncClient.fetchActive(clientName));
        System.out.println("[SYNC] " + clientName + " " + report);
        return report;
    }

    /** Full resume: register again, then synchronize. */
    public SyncReport resume() throws Exception {
        if (!registration.registerAgain()) {
            throw new IOException("Registration was denied, cannot resume");
        }

        try {
            return synchronize();
        } catch (SyncDeniedException denied) {
            // Rare race: the server restarted again between REGISTER and the sync.
            if (!NOT_REGISTERED_REASON.equals(denied.reason())) {
                throw denied;
            }

            if (!registration.registerAgain()) {
                throw new IOException("Registration was denied, cannot resume");
            }
            return synchronize();
        }
    }
}
