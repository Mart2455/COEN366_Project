package com.cenrs.client.notification;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle.Control;
import java.util.Set;
import java.util.function.Consumer;

import com.cenrs.contract.port.TransportPort;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;
import com.cenrs.protocol.UdpMessageCodec;

public class ClientNotificationService 
{
    



    private final String clientName;
    private final TransportPort transport;
    private final UdpMessageCodec codec;
    private final Consumer<ControlMessage> display;
    private final Map<String, Long> latestVersionByIncidentId = new HashMap<>();
    private final Set<String> closeIncidentIds = new HashSet<>();


    
    public ClientNotificationService(String clientName , TransportPort transport, UdpMessageCodec codec , Consumer<ControlMessage> display)
    {
        // Make sure to check for null arguments
        this.clientName = Objects.requireNonNull(clientName);
        this.transport = Objects.requireNonNull(transport);
        this.codec = Objects.requireNonNull(codec);
        this.display = Objects.requireNonNull(display);

    }

    public void handleNotification(ControlMessage notification , InetSocketAddress serverAddress) throws IOException, ProtocolException
    {
        // gotta check if the message is critical

        String incidentId = notification.field(1); // retrieve incident id used when sending the acknowledgement


        // ACK has to be sent for one of these alerts
        boolean requiresAck = switch(notification.type())
        {
            case ALERT:
                yield "CRITICAL".equals(notification.field(5));
            case ALERT_UPDATE:
                yield "CRITICAL".equals(notification.field(3));
            case ALL_CLEAR:
                yield true;
            default:
        throw new ProtocolException("Unsupported notification type"); // Have to include default case for all cases
        };

        ControlMessage acknowledgement;

        // Acknowledge retransmissions before checking for duplicate notifications.
        if (requiresAck)
        {
            acknowledgement = ControlMessage.of(MessageType.ALERT_ACK, notification.field(0) , incidentId , notification.field(2) , clientName);
            transport.send(serverAddress , codec.encode(acknowledgement)); // send in UDP
        }

        // Need to compare the version with the incidentid version
        // Compare the version by using long to see if version is equal, older, or newer
        Long version = Long.parseLong(notification.field(2));
        Long latestVersion = latestVersionByIncidentId.get(incidentId);

        if (latestVersion != null && version <= latestVersion)
        {
            return;
        }

        latestVersionByIncidentId.put(incidentId , version); // store back new version before displaying
        

        // Next thing is to check if the notification has been cleared

        if (notification.type() == MessageType.ALL_CLEAR)
        {
            // update the closed incident map
            closeIncidentIds.add(incidentId);
        }

        // accept displaying  the notification
        display.accept(notification);
    }

    

}
