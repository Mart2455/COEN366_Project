package com.cenrs.server.incident;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.Incident;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.port.IncidentEventSinkPort;
import com.cenrs.contract.port.IncidentIdGeneratorPort;
import com.cenrs.contract.port.IncidentStorePort;
import com.cenrs.contract.port.RegistryPort;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.contract.Severity;
import com.cenrs.contract.IncidentStatus;

import java.net.InetSocketAddress;
import java.util.Objects;



public class IncidentService {
    private final IncidentStorePort store;
    private final RegistryPort registry;
    private final IncidentIdGeneratorPort idGenerator;
    private final IncidentEventSinkPort eventSink;

    public IncidentService(IncidentStorePort store,RegistryPort registry, IncidentIdGeneratorPort idGenerator, IncidentEventSinkPort eventSink) 
    {
        this.store = Objects.requireNonNull(store);
        this.registry = Objects.requireNonNull(registry);
        this.idGenerator = Objects.requireNonNull(idGenerator);
        this.eventSink = Objects.requireNonNull(eventSink);
    }

    public ControlMessage publish(ControlMessage request , InetSocketAddress sender)
    {

        //  see if client is registed
        var publisher = registry.findAll().stream().filter(client -> client.ipAddress().equals(sender.getAddress().getHostAddress()) 
                                                          && client.udpPort() == sender.getPort()).findFirst();
        
        // If its empty we send an alert denied
        if (publisher.isEmpty())
        {
            return ControlMessage.of(MessageType.ALERT_DENIED , request.field(0) , "Client is not registered.");
        }                                                  

        // Need to check if publisher can publish
        if (!publisher.get().role().canPublish())
        {
            return ControlMessage.of(MessageType.ALERT_DENIED, request.field(0) , "Uauthorized role");
        }

        // No more checks, we can create the incident
        Incident incident;
        try
        {
            incident = new Incident(idGenerator.nextId(), 1, 
                                        AlertCategory.valueOf(request.field(1)), 
                                        CampusLocation.valueOf(request.field(2)), 
                                        Severity.valueOf(request.field(3)), 
                                        IncidentStatus.ACTIVE, request.field(4), 
                                        request.field(5), 
                                        request.field(6),
                                        publisher.get().name()
                                        );
        // Check to see if id already exist
        } 
        catch (IllegalArgumentException exception) 
        {
            return ControlMessage.of(
            MessageType.ALERT_DENIED,
            request.field(0),
            "Invalid incident fields"
            );
        }
        
        if (!store.create(incident))
        {
            return ControlMessage.of(MessageType.ALERT_DENIED , 
                                    request.field(0),
                                    "Incident ID already exist."
            );
        }                                

        // Return the incident
        eventSink.onCreated(incident);
        return ControlMessage.of(MessageType.ALERT_ACCEPTED, request.field(0) , incident.incidentId());
    }
}
