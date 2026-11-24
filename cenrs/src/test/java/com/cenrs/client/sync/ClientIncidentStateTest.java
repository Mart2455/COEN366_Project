package com.cenrs.client.sync;

import com.cenrs.client.sync.ClientIncidentState.Outcome;
import com.cenrs.client.sync.ClientIncidentState.SyncReport;
import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.Severity;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;

import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClientIncidentStateTest {

    @Test
    public void versionRuleDecidesWhatIsKept() {
        ClientIncidentState state = new ClientIncidentState();

        assertEquals(Outcome.NEW, state.apply(incident("INC100", 1)));
        assertEquals(Outcome.DUPLICATE, state.apply(incident("INC100", 1)));
        assertEquals(Outcome.UPDATED, state.apply(incident("INC100", 3)));
        assertEquals(Outcome.STALE, state.apply(incident("INC100", 2)));

        assertEquals(3, state.find("INC100").orElseThrow().version());
    }

    @Test
    public void reconcileAddsUpdatesAndDropsWhatClosedWhileAway() {
        ClientIncidentState state = new ClientIncidentState();
        state.apply(incident("INC100", 1));   // will be updated
        state.apply(incident("INC101", 2));   // unchanged
        state.apply(incident("INC050", 1));   // closed while we were offline

        SyncReport report = state.reconcile(List.of(
                incident("INC100", 2),
                incident("INC101", 2),
                incident("INC102", 1)          // new while we were offline
        ));

        assertEquals(List.of("INC102"), report.added());
        assertEquals(List.of("INC100"), report.updated());
        assertEquals(List.of("INC101"), report.unchanged());
        assertEquals(List.of("INC050"), report.noLongerActive());

        assertEquals(
                List.of("INC100", "INC101", "INC102"),
                state.activeIncidents().stream().map(KnownIncident::incidentId).toList()
        );
    }

    @Test
    public void serverNeverMovesAVersionBackwards() {
        ClientIncidentState state = new ClientIncidentState();
        state.apply(incident("INC100", 4));  // newer UDP update already received

        SyncReport report = state.reconcile(List.of(incident("INC100", 3)));

        assertEquals(List.of("INC100"), report.unchanged());
        assertEquals(4, state.find("INC100").orElseThrow().version());
    }

    @Test
    public void activeAlertRecordIsParsed() throws Exception {
        KnownIncident incident = KnownIncident.fromActiveAlert(
                ControlMessage.of(
                        MessageType.ACTIVE_ALERT,
                        "INC102", "2", "FIRE", "HALL", "WARNING", "ACTIVE",
                        "Fire Controlled", "Remain outside", "map.pdf"
                )
        );

        assertEquals("INC102", incident.incidentId());
        assertEquals(2, incident.version());
        assertEquals(Severity.WARNING, incident.severity());
        assertEquals("map.pdf", incident.attachment());
    }

    @Test
    public void invalidRecordIsRejected() {
        try {
            KnownIncident.fromActiveAlert(
                    ControlMessage.of(
                            MessageType.ACTIVE_ALERT,
                            "INC102", "two", "FIRE", "HALL", "WARNING", "ACTIVE",
                            "Fire", "Leave", "NONE"
                    )
            );
        } catch (ProtocolException expected) {
            assertTrue(expected.getMessage().contains("ACTIVE_ALERT"));
            return;
        }
        throw new AssertionError("Expected an invalid record");
    }

    static KnownIncident incident(String id, long version) {
        return new KnownIncident(
                id, version, AlertCategory.FIRE, CampusLocation.HALL,
                Severity.CRITICAL, IncidentStatus.ACTIVE,
                "Fire Alarm", "Evacuate", "NONE"
        );
    }
}
