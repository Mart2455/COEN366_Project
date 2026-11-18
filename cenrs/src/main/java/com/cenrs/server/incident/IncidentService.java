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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.ResourceBundle.Control;
import java.util.Set;



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

        String requestNumber = request.field(0);

        // Reject invalid request numbers before allocating an ID or changing state.
        if (!validRequestNumber(requestNumber)) 
        {
            return ControlMessage.of(
                    MessageType.ALERT_DENIED,
                    requestNumber,
                    "Invalid request number"
            );
        }

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



    public ControlMessage update(ControlMessage request, InetSocketAddress sender)
    {
        // Need to get the request number and incident ID

        String requestNumber = request.field(0);

        // Reject invalid request numbers before looking up or changing state.
        if (!validRequestNumber(requestNumber)) 
        {
            return ControlMessage.of(
                    MessageType.ALERT_DENIED,
                    requestNumber,
                    "Invalid request number"
            );
        }


        String incidentId = request.field(1);

        // FInd registered client with UDP endpoint
        var publisher = registry.findAll().stream().filter(client -> 
            client.ipAddress().equals(sender.getAddress().getHostAddress()) && 
                                    client.udpPort() == sender.getPort()).findFirst();        


            // if publisher doesntexist

            if (publisher.isEmpty())
            {
                return ControlMessage.of(
                MessageType.ALERT_DENIED,
                requestNumber,
                "Client is not registered"
                );
            }

            // Check that the registered client has a publisher-capable role.
            if (!publisher.get().role().canPublish()) 
            {
                return ControlMessage.of(
                        MessageType.ALERT_DENIED,
                        requestNumber,
                        "Unauthorized role"
                );
            }

            // Find the incident that the client wants to update.
            var existing = store.findById(incidentId);

            if (existing.isEmpty()) 
            {
                return ControlMessage.of(
                        MessageType.ALERT_DENIED,
                        requestNumber,
                        "Unknown incident"
                );
            }

            Incident current = existing.get();

            // Closed incidents cannot be updated.
            if (current.status() != IncidentStatus.ACTIVE) {
                return ControlMessage.of(
                        MessageType.ALERT_DENIED,
                        requestNumber,
                        "Incident is closed"
                );
            }

            // Only the original publisher may modify this incident.
            if (!current.publisherName().equals(publisher.get().name())) {
                return ControlMessage.of(
                        MessageType.ALERT_DENIED,
                        requestNumber,
                        "Only the creator may update this incident"
                );
            }

            Incident updated;

            try 
            {
                // Replace editable details while preserving incident identity
                // and metadata. Increment the version for the new state.
                updated = new Incident(
                        current.incidentId(),
                        current.version() + 1,
                        current.category(),
                        current.location(),
                        Severity.valueOf(request.field(2)),
                        current.status(),
                        request.field(3),
                        request.field(4),
                        current.attachment(),
                        current.publisherName()
                );
            } 
            catch (IllegalArgumentException exception) 
            {
                return ControlMessage.of(
                        MessageType.ALERT_DENIED,
                        requestNumber,
                        "Invalid incident fields"
                );
            }

            // Reject if another operation changed the incident since we read it.
            if (!store.replaceIfCurrent(current, updated)) 
            {
                return ControlMessage.of(
                        MessageType.ALERT_DENIED,
                        requestNumber,
                        "Incident changed; retry with a new request"
                );
            }
            
            // Emit the update only after successful storage.
            eventSink.onUpdated(updated);

            return ControlMessage.of(
                    MessageType.ALERT_UPDATED,
                    requestNumber,
                    updated.incidentId(),
                    Long.toString(updated.version())
            );

    }


    public ControlMessage close(ControlMessage request, InetSocketAddress sender) 
    {
        if (request.type() != MessageType.CLOSE_ALERT) 
        {
            throw new IllegalArgumentException("Expected CLOSE_ALERT");
        }

        String requestNumber = request.field(0);
        String incidentId = request.field(1);
        String reason = request.field(2);

        try 
        {
            if (Long.parseLong(requestNumber) <= 0) 
            {
                throw new NumberFormatException();
            }
        } 
        catch (NumberFormatException exception) 
        {
            return closeDenied(requestNumber, "Invalid request number");
        }

        var publisher = registry.findAll().stream()
                .filter(client -> sender.getAddress() != null
                        && client.ipAddress().equals(sender.getAddress().getHostAddress())
                        && client.udpPort() == sender.getPort())
                .findFirst();

        if (publisher.isEmpty()) 
        {
            return closeDenied(requestNumber, "Client is not registered");
        }
        if (!publisher.get().role().canPublish()) 
        {
            return closeDenied(requestNumber, "Unauthorized role");
        }

        var existing = store.findById(incidentId);
        if (existing.isEmpty()) 
        {
            return closeDenied(requestNumber, "Unknown incident");
        }

        Incident current = existing.get();
        if (current.status() != IncidentStatus.ACTIVE) 
        {
            return closeDenied(requestNumber, "Incident is already closed");
        }
        if (!current.publisherName().equals(publisher.get().name())) 
        {
            return closeDenied(requestNumber, "Only the creator may close this incident");
        }

        // Validate the reason before changing any stored state.
        if (reason.isBlank() || reason.contains("|") || reason.contains("\r")
                || reason.contains("\n")
                || reason.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256) 
        {
            return closeDenied(requestNumber, "Invalid closure reason");
        }

        long nextVersion;
        try 
        {
            nextVersion = Math.incrementExact(current.version());
        } 
        catch (ArithmeticException exception) 
        {
            return closeDenied(requestNumber, "Incident version limit reached");
        }

        // Preserve the incident's details and metadata; closure is not deletion.
        Incident closed = new Incident(
                current.incidentId(), nextVersion,
                current.category(), current.location(), current.severity(),
                IncidentStatus.CLOSED, current.title(), current.description(),
                current.attachment(), current.publisherName()
        );

        // Prevent closure from overwriting a concurrent update or closure.
        if (!store.replaceIfCurrent(current, closed)) 
        {
            return closeDenied(requestNumber, "Incident changed; retry with a new request");
        }

        // Pass the reason to notification routing for the ALL_CLEAR message.
        eventSink.onClosed(closed, reason);
        return ControlMessage.of(
                MessageType.ALERT_CLOSED, requestNumber, closed.incidentId()
        );
    }

    private boolean validRequestNumber(String value) 
    {
        try 
        {
            return Long.parseLong(value) > 0;
        } 
        catch (NumberFormatException exception) 
        {
            return false;
        }
    }

    private ControlMessage closeDenied(String requestNumber, String reason) 
    {
        return ControlMessage.of(MessageType.ALERT_DENIED, requestNumber, reason);
    }

    
}
