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
    private IncidentService service;
    private ControlMessage request;
    private InetSocketAddress sender;

    @Before
    public void setUp() 
    {
        registry = new FakePorts.Registry();
        store = new FakePorts.Incidents();
        createdEvents = new ArrayList<>();

        IncidentIdGeneratorPort idGenerator = () -> "INC1";

        IncidentEventSinkPort eventSink = new IncidentEventSinkPort() {
            @Override
            public void onCreated(Incident incident) {
                createdEvents.add(incident);
            }

            @Override
            public void onUpdated(Incident incident) {
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

    
}
