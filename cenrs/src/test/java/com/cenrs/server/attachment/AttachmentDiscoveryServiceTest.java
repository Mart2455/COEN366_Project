package com.cenrs.server.attachment;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.Incident;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.Severity;
import com.cenrs.contract.fake.FakePorts;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AttachmentDiscoveryServiceTest 
{
    private static final String REQUEST_NUMBER = "91";
    private static final String INCIDENT_ID = "INC-1";
    private static final String FILE_NAME = "evacuation map.pdf";
    private static final String PUBLISHER_NAME = "CampusSecurity";

    private final FakePorts.Incidents incidents = new FakePorts.Incidents();
    private final FakePorts.Registry registry = new FakePorts.Registry();
    private final AttachmentDiscoveryService service = new AttachmentDiscoveryService(incidents, registry);

    @Before
    public void registerPublisher() {
        registry.addIfAbsent(new ClientProfile(
            PUBLISHER_NAME, ClientRole.PUBLISHER, "192.0.2.10", 5001, 6100
        ));
    }

    @Test
    public void returnsCurrentPublisherEndpointForMatchingAttachment() {
        storeIncident(IncidentStatus.ACTIVE, FILE_NAME);
        registry.remove(PUBLISHER_NAME);
        registry.addIfAbsent(new ClientProfile(
            PUBLISHER_NAME, ClientRole.PUBLISHER, "192.0.2.20", 5002, 6200
        ));

        ControlMessage response = requestAttachment(FILE_NAME);

        assertEquals(ControlMessage.of(
            MessageType.ATTACH_INFO, REQUEST_NUMBER, INCIDENT_ID,
            PUBLISHER_NAME, "192.0.2.20", "6200", FILE_NAME
        ), response);
    }

    @Test
    public void deniesUnknownIncident() {
        assertDenied(FILE_NAME, "Unknown incident");
    }

    @Test
    public void deniesClosedIncident() {
        storeIncident(IncidentStatus.CLOSED, FILE_NAME);

        assertDenied(FILE_NAME, "Incident is not Active");
    }

    @Test
    public void deniesIncidentWithoutAttachment() {
        storeIncident(IncidentStatus.ACTIVE, "NONE");

        assertDenied("NONE", "Incident has no Attachment");
    }

    @Test
    public void deniesWrongFilename() {
        storeIncident(IncidentStatus.ACTIVE, FILE_NAME);

        assertDenied(
            "campus map.pdf",
            "Requested filename does not match the incident attachment"
        );
    }

    @Test
    public void deniesDeregisteredPublisher() {
        storeIncident(IncidentStatus.ACTIVE, FILE_NAME);
        registry.remove(PUBLISHER_NAME);

        assertDenied(FILE_NAME, "Publisher Unavailable");
    }

    private void storeIncident(IncidentStatus status, String attachment) {
        incidents.create(new Incident(
            INCIDENT_ID, 1, AlertCategory.FIRE, CampusLocation.HALL,
            Severity.CRITICAL, status, "Fire alarm", "Smoke reported",
            attachment, PUBLISHER_NAME
        ));
    }

    private ControlMessage requestAttachment(String filename) {
        return service.findAttachmentEndpoint(ControlMessage.of(
            MessageType.ATTACH_REQ, REQUEST_NUMBER, INCIDENT_ID, filename
        ));
    }

    private void assertDenied(String filename, String reason) {
        assertEquals(ControlMessage.of(
            MessageType.ATTACH_DENIED, REQUEST_NUMBER, INCIDENT_ID, reason
        ), requestAttachment(filename));
    }
}
