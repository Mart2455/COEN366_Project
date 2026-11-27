package com.cenrs.client.notification;

import com.cenrs.contract.fake.FakePorts;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.UdpMessageCodec;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ClientNotificationServiceTest {
    /**
     * Simulates a server retry after the first ACK fails to reach it.
     * Both deliveries must send an ACK to the server with the original request
     * number, incident ID, version, and registered client name so the server
     * can match the reply to its pending notification and stop retrying.
     * Both deliveries carry the same incident version, so the user sees the
     * alert only once.
     */
    @Test
    public void acknowledgesDuplicateCriticalAlertButDisplaysItOnce() throws Exception {

        var transport = new FakePorts.Transport();
        var codec = new UdpMessageCodec();

        var displayedNotifications = new ArrayList<ControlMessage>();
        var serverAddress = new InetSocketAddress("127.0.0.1", 5000);

        
        var service = new ClientNotificationService(
            "Alice", transport, codec, displayedNotifications::add
        );
        var alert = ControlMessage.of(
            MessageType.ALERT, "83", "INC102", "1", "FIRE", "HALL",
            "CRITICAL", "Fire Alarm", "Evacuate the building", "NONE"
        );

        service.handleNotification(alert, serverAddress);
        service.handleNotification(alert, serverAddress);

        var expectedAck = ControlMessage.of(
            MessageType.ALERT_ACK, "83", "INC102", "1", "Alice"
        );
        var sentMessages = transport.sentMessages();

        assertEquals(2, sentMessages.size());
        for (var sentMessage : sentMessages) {
            assertEquals(serverAddress, sentMessage.destination());
            assertEquals(expectedAck, codec.decode(sentMessage.message()));
        }
        assertEquals(List.of(alert), displayedNotifications);
    }

    /**
     * A WARNING alert is displayed when its incident version is new.
     * The client sends ACKs for CRITICAL alerts, so the fake transport should
     * record zero outgoing messages for this WARNING.
     */
    @Test
    public void displaysWarningAlertWithoutAcknowledgingIt() throws Exception {
        var transport = new FakePorts.Transport();
        var displayedNotifications = new ArrayList<ControlMessage>();
        var serverAddress = new InetSocketAddress("127.0.0.1", 5000);
        var service = new ClientNotificationService(
            "Alice", transport, new UdpMessageCodec(), displayedNotifications::add
        );
        var alert = ControlMessage.of(
            MessageType.ALERT, "84", "INC102", "1", "FIRE", "HALL",
            "WARNING", "Fire Alarm", "Stay clear of the building", "NONE"
        );

        service.handleNotification(alert, serverAddress);

        assertEquals(List.of(alert), displayedNotifications);
        assertEquals(0, transport.sentMessages().size());
    }

    /**
     * A newer critical update is displayed after the original warning.
     * Its ACK identifies the update using the update's request number and version.
     */
    @Test
    public void displaysAndAcknowledgesNewerCriticalUpdate() throws Exception {
        var transport = new FakePorts.Transport();
        var codec = new UdpMessageCodec();
        var displayedNotifications = new ArrayList<ControlMessage>();
        var serverAddress = new InetSocketAddress("127.0.0.1", 5000);
        var service = new ClientNotificationService(
            "Alice", transport, codec, displayedNotifications::add
        );
        var alert = ControlMessage.of(
            MessageType.ALERT, "84", "INC102", "1", "FIRE", "HALL",
            "WARNING", "Fire Alarm", "Stay clear of the building", "NONE"
        );
        var update = ControlMessage.of(
            MessageType.ALERT_UPDATE, "85", "INC102", "2",
            "CRITICAL", "Fire Spreading", "Evacuate the building"
        );

        service.handleNotification(alert, serverAddress);
        service.handleNotification(update, serverAddress);

        assertEquals(List.of(alert, update), displayedNotifications);
        var sentMessages = transport.sentMessages();
        assertEquals(1, sentMessages.size());
        assertEquals(ControlMessage.of(
            MessageType.ALERT_ACK, "85", "INC102", "2", "Alice"
        ), codec.decode(sentMessages.get(0).message()));
    }

    /**
     * ALL_CLEAR always requires an ACK. A delayed critical alert still
     * receives its own ACK so the server can stop retrying, and its older
     * version keeps it from being displayed after closure.
     */
    @Test
    public void acknowledgesAllClearAndSuppressesOlderCriticalAlert() throws Exception {
        var transport = new FakePorts.Transport();
        var codec = new UdpMessageCodec();
        var displayedNotifications = new ArrayList<ControlMessage>();
        var serverAddress = new InetSocketAddress("127.0.0.1", 5000);
        var service = new ClientNotificationService(
            "Alice", transport, codec, displayedNotifications::add
        );
        var allClear = ControlMessage.of(
            MessageType.ALL_CLEAR, "86", "INC102", "3", "Building reopened"
        );
        var lateAlert = ControlMessage.of(
            MessageType.ALERT, "83", "INC102", "1", "FIRE", "HALL",
            "CRITICAL", "Fire Alarm", "Evacuate the building", "NONE"
        );

        service.handleNotification(allClear, serverAddress);
        service.handleNotification(lateAlert, serverAddress);

        assertEquals(List.of(allClear), displayedNotifications);
        var sentMessages = transport.sentMessages();
        assertEquals(2, sentMessages.size());
        assertEquals(ControlMessage.of(
            MessageType.ALERT_ACK, "86", "INC102", "3", "Alice"
        ), codec.decode(sentMessages.get(0).message()));
        assertEquals(ControlMessage.of(
            MessageType.ALERT_ACK, "83", "INC102", "1", "Alice"
        ), codec.decode(sentMessages.get(1).message()));
    }
}
