package com.cenrs.server.subscription;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.Subscription;
import com.cenrs.contract.fake.FakePorts;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;
import com.cenrs.protocol.UdpMessageCodec;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SubscriptionServiceTest {
    private FakePorts.Registry registry;
    private FakePorts.Subscriptions subscriptions;
    private FakePorts.Transport transport;
    private UdpMessageCodec codec;
    private SubscriptionService service;

    @Before
    public void setUp() {
        registry = new FakePorts.Registry();
        subscriptions = new FakePorts.Subscriptions();
        transport = new FakePorts.Transport();
        codec = new UdpMessageCodec();

        service = new SubscriptionService(
                registry,
                subscriptions,
                transport,
                codec
        );
    }

    @Test
    public void subscriberAndBothCanMaintainSeveralSubscriptions()
            throws Exception {

        InetSocketAddress alice = addClient(
                "Alice",
                ClientRole.SUBSCRIBER,
                5001
        );

        InetSocketAddress bob = addClient(
                "Bob",
                ClientRole.BOTH,
                5002
        );

        service.handle(
                subscribe("1", "HALL", "FIRE"),
                alice
        );

        service.handle(
                subscribe("2", "EV", "WEATHER"),
                alice
        );

        service.handle(
                subscribe("3", "ALL", "SECURITY"),
                bob
        );

        assertEquals(
                2,
                subscriptions
                        .findByClient("Alice")
                        .size()
        );

        assertEquals(
                1,
                subscriptions
                        .findByClient("Bob")
                        .size()
        );

        assertEquals(
                MessageType.SUBSCRIBED,
                responseAt(0).type()
        );

        assertEquals("1", responseAt(0).field(0));
        assertEquals("2", responseAt(1).field(0));
        assertEquals("3", responseAt(2).field(0));
    }

    @Test
    public void publisherAndUnregisteredClientAreDenied()
            throws Exception {

        InetSocketAddress publisher = addClient(
                "Publisher",
                ClientRole.PUBLISHER,
                5001
        );

        service.handle(
                subscribe("1", "HALL", "FIRE"),
                publisher
        );

        service.handle(
                subscribe("2", "HALL", "FIRE"),
                new InetSocketAddress(
                        "127.0.0.1",
                        5999
                )
        );

        assertDenied(
                responseAt(0),
                "1",
                "Role cannot subscribe"
        );

        assertDenied(
                responseAt(1),
                "2",
                "Client is not registered"
        );

        assertTrue(
                subscriptions
                        .findByClient("Publisher")
                        .isEmpty()
        );
    }

    @Test
    public void invalidLocationAndCategoryAreDenied()
            throws Exception {

        InetSocketAddress alice = addClient(
                "Alice",
                ClientRole.SUBSCRIBER,
                5001
        );

        service.handle(
                subscribe(
                        "1",
                        "DOWNTOWN",
                        "FIRE"
                ),
                alice
        );

        service.handle(
                subscribe(
                        "2",
                        "HALL",
                        "MEDICAL"
                ),
                alice
        );

        assertDenied(
                responseAt(0),
                "1",
                "Invalid location"
        );

        assertDenied(
                responseAt(1),
                "2",
                "Invalid category"
        );

        assertTrue(
                subscriptions
                        .findByClient("Alice")
                        .isEmpty()
        );
    }

    @Test
    public void duplicateSubscribeAndUnsubscribeAreIdempotent()
            throws Exception {

        InetSocketAddress alice = addClient(
                "Alice",
                ClientRole.SUBSCRIBER,
                5001
        );

        ControlMessage request =
                subscribe("1", "HALL", "FIRE");

        service.handle(request, alice);
        service.handle(request, alice);

        assertEquals(
                1,
                subscriptions
                        .findByClient("Alice")
                        .size()
        );

        assertEquals(
                2,
                transport.sentMessages().size()
        );

        ControlMessage unsubscribe =
                ControlMessage.of(
                        MessageType.UNSUBSCRIBE,
                        "2",
                        "HALL",
                        "FIRE"
                );

        service.handle(unsubscribe, alice);
        service.handle(unsubscribe, alice);

        assertTrue(
                subscriptions
                        .findByClient("Alice")
                        .isEmpty()
        );

        assertEquals(
                2,
                transport.sentMessages().size()
        );
    }

    @Test
    public void wildcardMatchingReturnsEachClientOnce() {
        addClient(
                "Alice",
                ClientRole.SUBSCRIBER,
                5001
        );

        addClient(
                "Bob",
                ClientRole.BOTH,
                5002
        );

        addClient(
                "Carol",
                ClientRole.SUBSCRIBER,
                5003
        );

        addClient(
                "Publisher",
                ClientRole.PUBLISHER,
                5004
        );

        subscriptions.add(
                "Alice",
                new Subscription(
                        CampusLocation.HALL,
                        AlertCategory.FIRE
                )
        );

        subscriptions.add(
                "Alice",
                new Subscription(
                        CampusLocation.ALL,
                        AlertCategory.FIRE
                )
        );

        subscriptions.add(
                "Bob",
                new Subscription(
                        CampusLocation.HALL,
                        AlertCategory.ALL
                )
        );

        subscriptions.add(
                "Carol",
                new Subscription(
                        CampusLocation.EV,
                        AlertCategory.WEATHER
                )
        );

        subscriptions.add(
                "Publisher",
                new Subscription(
                        CampusLocation.ALL,
                        AlertCategory.ALL
                )
        );

        Set<String> matchingNames =
                service.findMatchingSubscribers(
                                CampusLocation.HALL,
                                AlertCategory.FIRE
                        )
                        .stream()
                        .map(ClientProfile::name)
                        .collect(Collectors.toSet());

        assertEquals(
                Set.of("Alice", "Bob"),
                matchingNames
        );
    }

    private InetSocketAddress addClient(
            String name,
            ClientRole role,
            int udpPort
    ) {
        ClientProfile client = new ClientProfile(
                name,
                role,
                "127.0.0.1",
                udpPort,
                6000 + udpPort
        );

        registry.addIfAbsent(client);

        return new InetSocketAddress(
                client.ipAddress(),
                client.udpPort()
        );
    }

    private ControlMessage subscribe(
            String requestNumber,
            String location,
            String category
    ) {
        return ControlMessage.of(
                MessageType.SUBSCRIBE,
                requestNumber,
                location,
                category
        );
    }

    private ControlMessage responseAt(int index)
            throws ProtocolException {

        return codec.decode(
                transport.sentMessages()
                        .get(index)
                        .message()
        );
    }

    private void assertDenied(
            ControlMessage response,
            String requestNumber,
            String reason
    ) {
        assertEquals(
                MessageType.SUBSCRIPTION_DENIED,
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