package com.cenrs.server.incident;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.Incident;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.Severity;
import com.cenrs.contract.fake.FakePorts;
import com.cenrs.contract.port.IncidentEventSinkPort;
import com.cenrs.contract.port.IncidentIdGeneratorPort;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;

public class IncidentServiceTest 
{
    private FakePorts.Registry registry;
    private FakePorts.Incidents store;
    private List<Incident> createdEvents;
    private List<Incident> updatedEvents;
    private IncidentService service;
    private ControlMessage request;
    private InetSocketAddress sender;

    @Before
    public void setUp() 
    {
        registry = new FakePorts.Registry();
        store = new FakePorts.Incidents();
        createdEvents = new ArrayList<>();
        updatedEvents = new ArrayList<>();

        IncidentIdGeneratorPort idGenerator = () -> "INC1";

        IncidentEventSinkPort eventSink = new IncidentEventSinkPort() {
            @Override
            public void onCreated(Incident incident) {
                createdEvents.add(incident);
            }

            @Override
            public void onUpdated(Incident incident) {
                updatedEvents.add(incident);
            }

            @Override
            public void onClosed(Incident incident) {
            }
        };

        service = new IncidentService(
                store, registry, idGenerator, eventSink
        );

        request = ControlMessage.of(
                MessageType.PUBLISH_ALERT,
                "1", "FIRE", "HALL", "CRITICAL",
                "Fire Alarm", "Evacuate the Building", "NONE"
        );

        sender = new InetSocketAddress("127.0.0.1", 5001);
    }

    @Test
    public void validPublisherCreatesActiveIncident() 
    {
        // Arrange: register the publisher in the fresh registry.
        registry.addIfAbsent(new ClientProfile(
                "ALI", ClientRole.PUBLISHER, "127.0.0.1", 5001, 6001
        ));

        // Act
        ControlMessage response = service.publish(request, sender);

        // Assert: verify the acceptance response.
        assertEquals(MessageType.ALERT_ACCEPTED, response.type());
        assertEquals("1", response.field(0));
        assertEquals("INC1", response.field(1));

        // Verify the incident was stored.
        var saved = store.findById("INC1");
        assertTrue(saved.isPresent());
        Incident incident = saved.get();

        assertEquals(IncidentStatus.ACTIVE, incident.status());
        assertEquals(1L, incident.version());
        assertEquals("ALI", incident.publisherName());
        assertEquals("NONE", incident.attachment());

        // Verify the created event contains the stored incident.
        assertEquals(1, createdEvents.size());
        assertEquals(incident, createdEvents.get(0));
    }

    @Test
    public void unregisteredClientCannotPublish() {
        // Arrange: setUp leaves the registry empty.

        // Act
        ControlMessage response = service.publish(request, sender);

        // Assert
        assertEquals(MessageType.ALERT_DENIED, response.type());
        assertEquals("1", response.field(0));
        assertTrue(store.findActive().isEmpty());
        assertTrue(createdEvents.isEmpty());
    }

    @Test
    public void subscriberCannotPublish() 
    {
        registry.addIfAbsent(new ClientProfile(
                "ALI", ClientRole.SUBSCRIBER,
                "127.0.0.1", 5001, 6001
        ));

        ControlMessage response = service.publish(request, sender);

        // Check if alert is indeed denied
        assertEquals(MessageType.ALERT_DENIED, response.type());
        
        assertEquals("1", response.field(0));
        assertTrue(store.findActive().isEmpty());
        assertTrue(createdEvents.isEmpty());
    }

    @Test
    public void invalidSeverityIsDenied() 
    {
        registry.addIfAbsent(new ClientProfile(
                "ALI", ClientRole.PUBLISHER,
                "127.0.0.1", 5001, 6001
        ));

        ControlMessage invalidRequest = ControlMessage.of(
                MessageType.PUBLISH_ALERT,
                "1", "FIRE", "HALL", "INVALID",
                "Fire Alarm", "Evacuate the Building", "NONE"
        );

        ControlMessage response = service.publish(invalidRequest, sender);

        assertEquals(MessageType.ALERT_DENIED, response.type());
        assertEquals("1", response.field(0));
        assertTrue(store.findActive().isEmpty());
        assertTrue(createdEvents.isEmpty());
    }

    @Test
    public void bothRoleCanPublishWithAttachment() 
    {
        registry.addIfAbsent(new ClientProfile(
                "ALI", ClientRole.BOTH,
                "127.0.0.1", 5001, 6001
        ));

        ControlMessage attachmentRequest = ControlMessage.of(
                MessageType.PUBLISH_ALERT,
                "1", "FIRE", "HALL", "CRITICAL",
                "Fire Alarm", "Evacuate the Building",
                "evacuation_map.pdf"
        );

        ControlMessage response = service.publish(attachmentRequest, sender);

        assertEquals(MessageType.ALERT_ACCEPTED, response.type());

        Incident incident = store.findById("INC1").orElseThrow();
        assertEquals("evacuation_map.pdf", incident.attachment());
        assertEquals("ALI", incident.publisherName());
        assertEquals(1, createdEvents.size());
        assertEquals(incident, createdEvents.get(0));
    }

    

    @Test
    public void creatorCanUpdateActiveIncident() {
        // Arrange: register the creator and publish the original incident.
        registry.addIfAbsent(new ClientProfile(
                "ALI", ClientRole.PUBLISHER,
                "127.0.0.1", 5001, 6001
        ));
        service.publish(request, sender);
        Incident original = store.findById("INC1").orElseThrow();

        ControlMessage updateRequest = ControlMessage.of(
                MessageType.UPDATE_ALERT,
                "2", "INC1", "WARNING",
                "Fire controlled", "Remain outside"
        );

        // Act: update the incident from its creator's endpoint.
        ControlMessage response = service.update(updateRequest, sender);

        // Verify acceptance includes the request number, ID, and new version.
        assertEquals(MessageType.ALERT_UPDATED, response.type());
        assertEquals("2", response.field(0));
        assertEquals("INC1", response.field(1));
        assertEquals("2", response.field(2));

        Incident updated = store.findById("INC1").orElseThrow();

        // Verify the stored version increased and new details were applied.
        assertEquals(2L, updated.version());
        assertEquals(Severity.WARNING, updated.severity());
        assertEquals("Fire controlled", updated.title());
        assertEquals("Remain outside", updated.description());

        // Verify identity, metadata, ownership, and active status were preserved.
        assertEquals(original.incidentId(), updated.incidentId());
        assertEquals(original.category(), updated.category());
        assertEquals(original.location(), updated.location());
        assertEquals(original.attachment(), updated.attachment());
        assertEquals(original.publisherName(), updated.publisherName());
        assertEquals(IncidentStatus.ACTIVE, updated.status());

        // Verify exactly one update event contains the newly stored incident.
        assertEquals(1, updatedEvents.size());
        assertEquals(updated, updatedEvents.get(0));
    }
}
