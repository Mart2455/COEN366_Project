package com.cenrs.server.attachment;

import com.cenrs.contract.Incident;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.port.IncidentStorePort;
import com.cenrs.contract.port.RegistryPort;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;

public class AttachmentDiscoveryService 
{
        private final IncidentStorePort incidentStore;
        private final RegistryPort registry;


        public AttachmentDiscoveryService(IncidentStorePort incidentStore , RegistryPort registry)
        {
            this.incidentStore = incidentStore;
            this.registry = registry;
        }


        public ControlMessage findAttachmentEndpoint(ControlMessage request)
        {
            
            String incidentId = request.field(1); // gets the incident ID 
            var incident = incidentStore.findById(incidentId);

            if (incident.isEmpty())
            {
                return ControlMessage.of(MessageType.ATTACH_DENIED, request.field(0), incidentId, "Unknown incident");
            }

            if(incident.get().status() != IncidentStatus.ACTIVE)
            {
                return ControlMessage.of(MessageType.ATTACH_DENIED , request.field(0) , incidentId , "Incident is not Active");
            }

            if (!incident.get().hasAttachment())
            {
                return ControlMessage.of(MessageType.ATTACH_DENIED , request.field(0) , incidentId , "Incident has no Attachment");
            }

            if (!incident.get().attachment().equals(request.field(2)))
            {
                return ControlMessage.of(MessageType.ATTACH_DENIED , request.field(0) , 
                                        incidentId , "Requested filename does not match the incident attachment");
            }

            var publisherName = incident.get().publisherName();

            var publisher = registry.findByName(publisherName);

            if (publisher.isEmpty())
            {
                return ControlMessage.of(MessageType.ATTACH_DENIED , request.field(0) , incidentId , "Publisher Unavailable");
            }

            // Gets the attributes to be sent
            var profile = publisher.get();
            String ipAddress = profile.ipAddress();
            String tcpPort = Integer.toString(profile.tcpPort());

            // send the information
            return ControlMessage.of(MessageType.ATTACH_INFO , request.field(0) , incidentId, 
                                    publisherName , ipAddress , tcpPort, request.field(2));
        }
}
