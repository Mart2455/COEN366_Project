package com.cenrs.server.sync;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.Incident;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.Severity;
import com.cenrs.contract.Subscription;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.server.incident.InMemoryIncidentStore;
import com.cenrs.server.state.ServerState;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ActiveAlertSyncServiceTest {

    private ServerState state;
    private InMemoryIncidentStore incidents;
    private ActiveAlertSyncService service;

    @Before
    public void setUp() {
        state = new ServerState();
        incidents = new InMemoryIncidentStore();
        service = new ActiveAlertSyncService(state, state, incidents);

        state.addIfAbsent(client("Alice", ClientRole.SUBSCRIBER));
        state.add("Alice", new Subscription(CampusLocation.HALL, AlertCategory.FIRE));
        state.add("Alice", new Subscription(CampusLocation.ALL, AlertCategory.WEATHER));
    }

    @Test
    public void returnsOnlyActiveIncidentsMatchingTheSubscriptions() {
        Incident hallFire = incident("INC100", AlertCategory.FIRE, CampusLocation.HALL, IncidentStatus.ACTIVE);
        Incident evWeather = incident("INC101", AlertCategory.WEATHER, CampusLocation.EV, IncidentStatus.ACTIVE);
        incidents.create(hallFire);
        incidents.create(evWeather);
        // Not returned: wrong location, wrong category, closed.
        incidents.create(incident("INC102", AlertCategory.FIRE, CampusLocation.EV, IncidentStatus.ACTIVE));
        incidents.create(incident("INC103", AlertCategory.POWER, CampusLocation.HALL, IncidentStatus.ACTIVE));
        incidents.create(incident("INC104", AlertCategory.FIRE, CampusLocation.HALL, IncidentStatus.CLOSED));

        List<ControlMessage> response = service.handle(request("22", "Alice"));

        assertEquals(3, response.size());
        assertEquals(ControlMessage.of(MessageType.ACTIVE_ALERTS, "22", "2"), response.get(0));
        assertEquals(
                ControlMessage.of(
                        MessageType.ACTIVE_ALERT,
                        "INC100", "2", "FIRE", "HALL", "WARNING", "ACTIVE",
                        "Fire Controlled", "Remain outside the building",
                        "evacuation_map.pdf"
                ),
                response.get(1)
        );
        assertEquals("INC101", response.get(2).field(0));
    }

    @Test
    public void allAllSubscriptionMatchesEveryActiveIncident() {
        state.addIfAbsent(client("Bob", ClientRole.BOTH));
        state.add("Bob", new Subscription(CampusLocation.ALL, AlertCategory.ALL));
        incidents.create(incident("INC100", AlertCategory.FIRE, CampusLocation.HALL, IncidentStatus.ACTIVE));
        incidents.create(incident("INC101", AlertCategory.POWER, CampusLocation.SGW, IncidentStatus.ACTIVE));

        assertEquals("2", service.handle(request("5", "Bob")).get(0).field(1));
    }

    @Test
    public void noMatchingIncidentGivesZeroAndNoRecords() {
        List<ControlMessage> response = service.handle(request("22", "Alice"));

        assertEquals(List.of(ControlMessage.of(MessageType.ACTIVE_ALERTS, "22", "0")), response);
    }

    @Test
    public void incidentsAreSortedByNumberNotAlphabetically() {
        incidents.create(incident("INC100", AlertCategory.FIRE, CampusLocation.HALL, IncidentStatus.ACTIVE));
        incidents.create(incident("INC99", AlertCategory.FIRE, CampusLocation.HALL, IncidentStatus.ACTIVE));

        List<ControlMessage> response = service.handle(request("1", "Alice"));

        assertEquals("INC99", response.get(1).field(0));
        assertEquals("INC100", response.get(2).field(0));
    }

    @Test
    public void unknownClientIsDenied() {
        assertDenied(service.handle(request("7", "Mallory")), "7", "Unknown client");
    }

    @Test
    public void clientThatDidNotRegisterAgainAfterRestartIsDenied() {
        state.addInactive(client("Carol", ClientRole.SUBSCRIBER));

        assertDenied(service.handle(request("8", "Carol")), "8", "Client not registered");
    }

    @Test
    public void publisherOnlyClientIsDenied() {
        state.addIfAbsent(client("Security", ClientRole.PUBLISHER));

        assertDenied(service.handle(request("9", "Security")), "9", "Unauthorized role");
    }

    @Test
    public void invalidRequestNumberIsDenied() {
        assertDenied(service.handle(request("abc", "Alice")), "abc", "Invalid request number");
    }

    private static void assertDenied(List<ControlMessage> response, String rq, String reason) {
        assertEquals(
                List.of(ControlMessage.of(MessageType.ACTIVE_ALERTS_DENIED, rq, reason)),
                response
        );
    }

    private static ControlMessage request(String requestNumber, String name) {
        return ControlMessage.of(MessageType.GET_ACTIVE_ALERTS, requestNumber, name);
    }

    private static ClientProfile client(String name, ClientRole role) {
        return new ClientProfile(name, role, "127.0.0.1", 5001, 6001);
    }

    static Incident incident(
            String id,
            AlertCategory category,
            CampusLocation location,
            IncidentStatus status
    ) {
        return new Incident(
                id, 2, category, location, Severity.WARNING, status,
                "Fire Controlled", "Remain outside the building",
                "evacuation_map.pdf", "Security"
        );
    }
}
