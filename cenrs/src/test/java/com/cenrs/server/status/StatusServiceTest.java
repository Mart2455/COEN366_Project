package com.cenrs.server.status;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.Incident;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.SafetyStatus;
import com.cenrs.contract.Severity;
import com.cenrs.contract.fake.FakePorts;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StatusServiceTest {
    private FakePorts.Registry registry;
    private FakePorts.Incidents incidents;
    private InMemoryStatusStore statuses;
    private StatusService service;
    private InetSocketAddress alice;

    @Before
    public void setUp() {
        registry = new FakePorts.Registry();
        incidents = new FakePorts.Incidents();
        statuses = new InMemoryStatusStore();

        service = new StatusService(
            registry,
            incidents,
            statuses
        );

        alice = addClient(
            "Alice",
            ClientRole.SUBSCRIBER,
            5001
        );

        incidents.create(
            incident(
                "INC102",
                IncidentStatus.ACTIVE
            )
        );
    }

    @Test
    public void validSubscriberStatusIsRecorded() {
        ControlMessage response = service.report(
            report(
                "91",
                "INC102",
                "Alice",
                "SAFE"
            ),
            alice
        );

        assertEquals(
            MessageType.STATUS_RECORDED,
            response.type()
        );
        assertEquals("91", response.field(0));
        assertEquals(
            SafetyStatus.SAFE,
            statuses
                .findLatest("INC102", "Alice")
                .orElseThrow()
                .status()
        );
    }

    @Test
    public void newerStatusReplacesPreviousStatus() {
        service.report(
            report(
                "91",
                "INC102",
                "Alice",
                "NEED_HELP"
            ),
            alice
        );

        service.report(
            report(
                "92",
                "INC102",
                "Alice",
                "LEFT_AREA"
            ),
            alice
        );

        assertEquals(
            SafetyStatus.LEFT_AREA,
            statuses
                .findLatest("INC102", "Alice")
                .orElseThrow()
                .status()
        );
    }

    @Test
    public void bothRoleCanReportStatus() {
        InetSocketAddress bob = addClient(
            "Bob",
            ClientRole.BOTH,
            5002
        );

        ControlMessage response = service.report(
            report(
                "93",
                "INC102",
                "Bob",
                "SAFE"
            ),
            bob
        );

        assertEquals(
            MessageType.STATUS_RECORDED,
            response.type()
        );
    }

    @Test
    public void invalidClientsAreRejected() {
        InetSocketAddress publisher = addClient(
            "Publisher",
            ClientRole.PUBLISHER,
            5003
        );

        ControlMessage unregistered = service.report(
            report(
                "94",
                "INC102",
                "Alice",
                "SAFE"
            ),
            new InetSocketAddress(
                "127.0.0.1",
                5999
            )
        );

        ControlMessage mismatched = service.report(
            report(
                "95",
                "INC102",
                "Bob",
                "SAFE"
            ),
            alice
        );

        ControlMessage wrongRole = service.report(
            report(
                "96",
                "INC102",
                "Publisher",
                "SAFE"
            ),
            publisher
        );

        assertRejected(
            unregistered,
            "94",
            "Client is not registered"
        );
        assertRejected(
            mismatched,
            "95",
            "Client name does not match sender"
        );
        assertRejected(
            wrongRole,
            "96",
            "Role cannot report status"
        );

        assertTrue(
            statuses
                .findLatest("INC102", "Alice")
                .isEmpty()
        );
    }

    @Test
    public void invalidStatusIsRejected() {
        ControlMessage response = service.report(
            report(
                "97",
                "INC102",
                "Alice",
                "MISSING"
            ),
            alice
        );

        assertRejected(
            response,
            "97",
            "Invalid status"
        );

        assertTrue(
            statuses
                .findLatest("INC102", "Alice")
                .isEmpty()
        );
    }

    @Test
    public void unknownAndClosedIncidentsAreRejected() {
        incidents.create(
            incident(
                "INC103",
                IncidentStatus.CLOSED
            )
        );

        ControlMessage unknown = service.report(
            report(
                "98",
                "INC999",
                "Alice",
                "SAFE"
            ),
            alice
        );

        ControlMessage closed = service.report(
            report(
                "99",
                "INC103",
                "Alice",
                "SAFE"
            ),
            alice
        );

        assertRejected(
            unknown,
            "98",
            "Unknown incident"
        );
        assertRejected(
            closed,
            "99",
            "Incident is closed"
        );
    }

    private InetSocketAddress addClient(
        String name,
        ClientRole role,
        int udpPort
    ) {
        registry.addIfAbsent(
            new ClientProfile(
                name,
                role,
                "127.0.0.1",
                udpPort,
                6000 + udpPort
            )
        );

        return new InetSocketAddress(
            "127.0.0.1",
            udpPort
        );
    }

    private Incident incident(
        String incidentId,
        IncidentStatus status
    ) {
        return new Incident(
            incidentId,
            1,
            AlertCategory.FIRE,
            CampusLocation.HALL,
            Severity.WARNING,
            status,
            "Fire alarm",
            "Leave the building",
            "NONE",
            "Publisher"
        );
    }

    private ControlMessage report(
        String requestNumber,
        String incidentId,
        String clientName,
        String status
    ) {
        return ControlMessage.of(
            MessageType.STATUS_REPORT,
            requestNumber,
            incidentId,
            clientName,
            status
        );
    }

    private void assertRejected(
        ControlMessage response,
        String requestNumber,
        String reason
    ) {
        assertEquals(
            MessageType.STATUS_REJECTED,
            response.type()
        );
        assertEquals(
            requestNumber,
            response.field(0)
        );
        assertEquals(
            reason,
            response.field(1)
        );
    }
}