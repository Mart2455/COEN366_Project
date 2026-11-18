package com.cenrs.server.incident;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.Request;

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
    

// Provide a fake registery that has 

@Test
public void validPublisherCreatesActiveIncident()
{
    // 1. Arrange: prepare dependencies and input

    // 2. Act: call the method being tested

    // 3. Assert: check the result

    


    FakePorts.Registry registry = new FakePorts.Registry();
    FakePorts.Incidents store = new FakePorts.Incidents();
    IncidentIdGeneratorPort idGenerator = () -> "INC1";
    List<Incident> createdEvents = new ArrayList<>();

    IncidentEventSinkPort eventSink = new IncidentEventSinkPort() {
        @Override
        public void onCreated(Incident incident) 
        {
            createdEvents.add(incident);
        }

        @Override
        public void onUpdated(Incident incident) 
        {
        }

        @Override
        public void onClosed(Incident incident) 
        {
        }
    };

    IncidentService service = new IncidentService(
            store,
            registry,
            idGenerator,
            eventSink
    );

    registry.addIfAbsent(new ClientProfile("ALI", ClientRole.PUBLISHER, "127.0.0.1", 5001, 6001));

    
    ControlMessage request = ControlMessage.of(
        MessageType.PUBLISH_ALERT,
        "1",
        "FIRE",
        "HALL",
        "CRITICAL",
        "Fire Alarm",
        "Evacuate the Building",
        "NONE"
    );
    InetSocketAddress sender = new InetSocketAddress(
        "127.0.0.1",
        5001
    );

    ControlMessage response = service.publish(request, sender);


    // Assert: verify the acceptance response
    assertEquals(MessageType.ALERT_ACCEPTED, response.type());
    assertEquals("1", response.field(0));
    assertEquals("INC1", response.field(1));

    // Verify the incident was stored
    var saved = store.findById("INC1");
    assertTrue(saved.isPresent());

    Incident incident = saved.get();

    assertEquals(IncidentStatus.ACTIVE, incident.status());
    assertEquals(1L, incident.version());
    assertEquals("ALI", incident.publisherName());
    assertEquals("NONE", incident.attachment());

    // Verify the created event contains the stored incident
    assertEquals(1, createdEvents.size());
    assertEquals(incident, createdEvents.get(0));

}




}
