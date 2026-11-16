package com.cenrs.server.registration;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.Subscription;
import com.cenrs.contract.fake.FakePorts;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;
import com.cenrs.protocol.UdpMessageCodec;

import java.net.InetSocketAddress;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RegistrationServiceTest {
    private FakePorts.Registry registry;
    private FakePorts.Subscriptions subscriptions;
    private FakePorts.Transport transport;
    private UdpMessageCodec codec;
    private RegistrationService service;

    @Before
    public void setUp() {
        registry = new FakePorts.Registry();
        subscriptions = new FakePorts.Subscriptions();
        transport = new FakePorts.Transport();
        codec = new UdpMessageCodec();

        service = new RegistrationService(
                registry,
                subscriptions,
                transport,
                codec
        );
    }

    @Test
    public void acceptsAllClientRolesAndReturnsCatalogs()
            throws Exception {

        for (ClientRole role : ClientRole.values()) {
            int number = role.ordinal() + 1;
            int udpPort = 5000 + number;
            String name = "Client" + number;

            InetSocketAddress sender =
                    new InetSocketAddress(
                            "127.0.0.1",
                            udpPort
                    );

            service.handle(
                    register(
                            Integer.toString(number),
                            name,
                            role.name(),
                            "127.0.0.1",
                            Integer.toString(udpPort),
                            "6001"
                    ),
                    sender
            );

            ControlMessage response =
                    responseAt(number - 1);

            assertEquals(
                    MessageType.REGISTERED,
                    response.type()
            );

            assertEquals(
                    Integer.toString(number),
                    response.field(0)
            );

            assertEquals(
                    "HALL,EV,JMSB,LOYOLA,SGW,ALL",
                    response.field(1)
            );

            assertEquals(
                    "FIRE,SECURITY,WEATHER,POWER,BUILDING,GENERAL,ALL",
                    response.field(2)
            );

            assertTrue(
                    registry.findByName(name).isPresent()
            );

            assertEquals(
                    sender,
                    transport.sentMessages()
                            .get(number - 1)
                            .destination()
            );
        }
    }

    @Test
    public void duplicateNameIsDenied()
            throws Exception {

        InetSocketAddress sender =
                new InetSocketAddress(
                        "127.0.0.1",
                        5001
                );

        service.handle(
                register(
                        "1",
                        "Alice",
                        "SUBSCRIBER",
                        "127.0.0.1",
                        "5001",
                        "6001"
                ),
                sender
        );

        service.handle(
                register(
                        "2",
                        "Alice",
                        "BOTH",
                        "127.0.0.1",
                        "5001",
                        "6002"
                ),
                sender
        );

        assertDenied(
                responseAt(1),
                "2",
                "Name already exists"
        );

        assertEquals(1, registry.findAll().size());
    }

    @Test
    public void invalidRoleAndRequestNumberAreDenied()
            throws Exception {

        InetSocketAddress sender =
                new InetSocketAddress(
                        "127.0.0.1",
                        5001
                );

        service.handle(
                register(
                        "1",
                        "Alice",
                        "VISITOR",
                        "127.0.0.1",
                        "5001",
                        "6001"
                ),
                sender
        );

        service.handle(
                register(
                        "0",
                        "Bob",
                        "SUBSCRIBER",
                        "127.0.0.1",
                        "5001",
                        "6001"
                ),
                sender
        );

        assertDenied(
                responseAt(0),
                "1",
                "Invalid role"
        );

        assertDenied(
                responseAt(1),
                "0",
                "Invalid request number"
        );

        assertTrue(registry.findAll().isEmpty());
    }

    @Test
    public void invalidOrMismatchedEndpointsAreDenied()
            throws Exception {

        InetSocketAddress sender =
                new InetSocketAddress(
                        "127.0.0.1",
                        5001
                );

        service.handle(
                register(
                        "1",
                        "Alice",
                        "SUBSCRIBER",
                        "127.0.0.1",
                        "70000",
                        "6001"
                ),
                sender
        );

        service.handle(
                register(
                        "2",
                        "Bob",
                        "SUBSCRIBER",
                        "127.0.0.1",
                        "5002",
                        "6002"
                ),
                sender
        );

        assertDenied(
                responseAt(0),
                "1",
                "Invalid UDP port"
        );

        assertDenied(
                responseAt(1),
                "2",
                "Endpoint does not match sender"
        );

        assertTrue(registry.findAll().isEmpty());
    }

    @Test
    public void deregistrationRemovesProfileAndSubscriptions()
            throws Exception {

        InetSocketAddress sender =
                new InetSocketAddress(
                        "127.0.0.1",
                        5001
                );

        service.handle(
                register(
                        "1",
                        "Alice",
                        "SUBSCRIBER",
                        "127.0.0.1",
                        "5001",
                        "6001"
                ),
                sender
        );

        subscriptions.add(
                "Alice",
                new Subscription(
                        CampusLocation.HALL,
                        AlertCategory.FIRE
                )
        );

        service.handle(
                ControlMessage.of(
                        MessageType.DE_REGISTER,
                        "2",
                        "Alice"
                ),
                sender
        );

        assertFalse(
                registry.findByName("Alice").isPresent()
        );

        assertTrue(
                subscriptions
                        .findByClient("Alice")
                        .isEmpty()
        );

        assertEquals(
                1,
                transport.sentMessages().size()
        );

        service.handle(
                register(
                        "3",
                        "Alice",
                        "BOTH",
                        "127.0.0.1",
                        "5001",
                        "6001"
                ),
                sender
        );

        assertTrue(
                registry.findByName("Alice").isPresent()
        );

        assertEquals(
                MessageType.REGISTERED,
                responseAt(1).type()
        );
    }

    @Test
    public void unknownOrForeignDeregistrationIsIgnored()
            throws Exception {

        InetSocketAddress alice =
                new InetSocketAddress(
                        "127.0.0.1",
                        5001
                );

        service.handle(
                register(
                        "1",
                        "Alice",
                        "SUBSCRIBER",
                        "127.0.0.1",
                        "5001",
                        "6001"
                ),
                alice
        );

        service.handle(
                ControlMessage.of(
                        MessageType.DE_REGISTER,
                        "2",
                        "Unknown"
                ),
                alice
        );

        service.handle(
                ControlMessage.of(
                        MessageType.DE_REGISTER,
                        "3",
                        "Alice"
                ),
                new InetSocketAddress(
                        "127.0.0.1",
                        5999
                )
        );

        assertTrue(
                registry.findByName("Alice").isPresent()
        );

        assertEquals(
                1,
                transport.sentMessages().size()
        );
    }

    private ControlMessage register(
            String requestNumber,
            String name,
            String role,
            String ipAddress,
            String udpPort,
            String tcpPort
    ) {
        return ControlMessage.of(
                MessageType.REGISTER,
                requestNumber,
                name,
                role,
                ipAddress,
                udpPort,
                tcpPort
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
                MessageType.REGISTER_DENIED,
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