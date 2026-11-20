package com.cenrs.server.delivery;

import com.cenrs.contract.fake.FakePorts;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AcknowledgementSchedulerTest {
    private FakePorts.Scheduler timer;
    private FakePorts.Transport transport;
    private InMemoryDeliveryStateRepository states;
    private AcknowledgementScheduler acknowledgements;
    private DeliveryKey key;

    @Before
    public void setUp() {
        timer = new FakePorts.Scheduler();
        transport = new FakePorts.Transport();
        states = new InMemoryDeliveryStateRepository();

        acknowledgements = new AcknowledgementScheduler(
            Clock.fixed(
                Instant.parse("2026-11-19T20:00:00Z"),
                ZoneOffset.UTC
            ),
            timer,
            states,
            transport
        );

        key = new DeliveryKey(
            83,
            "INC102",
            1,
            "Alice"
        );
    }

    @Test
    public void acknowledgementCancelsRetry() throws Exception {
        send(key, "ALERT|83|INC102");

        assertTrue(acknowledgements.acknowledge(key));
        timer.runPending();

        assertEquals(1, transport.sentMessages().size());
        assertEquals(
            DeliveryState.Status.CONFIRMED,
            states.find(key).orElseThrow().status()
        );
        assertEquals(0, acknowledgements.pendingCount());
    }

    @Test
    public void missingAcknowledgementStopsAfterThreeAttempts()
        throws Exception {

        send(key, "ALERT|83|INC102");

        timer.runPending();
        timer.runPending();
        timer.runPending();

        assertEquals(3, transport.sentMessages().size());

        DeliveryState state = states.find(key).orElseThrow();

        assertEquals(
            DeliveryState.Status.UNCONFIRMED,
            state.status()
        );
        assertEquals(3, state.attempts());
        assertEquals(0, acknowledgements.pendingCount());
    }

    @Test
    public void staleAcknowledgementIsIgnored() throws Exception {
        send(key, "ALERT|83|INC102");

        DeliveryKey stale = new DeliveryKey(
            83,
            "INC102",
            2,
            "Alice"
        );

        assertFalse(acknowledgements.acknowledge(stale));
        assertEquals(1, acknowledgements.pendingCount());
    }

    @Test
    public void duplicateAcknowledgementIsIgnored() throws Exception {
        send(key, "ALERT|83|INC102");

        assertTrue(acknowledgements.acknowledge(key));
        assertFalse(acknowledgements.acknowledge(key));
        assertEquals(1, transport.sentMessages().size());
    }

    @Test
    public void retriesReuseOriginalPayload() throws Exception {
        byte[] payload = "ALERT|83|INC102"
            .getBytes(StandardCharsets.UTF_8);

        acknowledgements.sendReliably(
            key,
            new InetSocketAddress("127.0.0.1", 5001),
            payload
        );

        payload[0] = 'X';
        timer.runPending();
        timer.runPending();

        byte[] first = transport.sentMessages().get(0).message();
        assertArrayEquals(
            first,
            transport.sentMessages().get(1).message()
        );
        assertArrayEquals(
            first,
            transport.sentMessages().get(2).message()
        );
    }

    @Test
    public void severalDeliveriesRetryIndependently()
        throws Exception {

        DeliveryKey second = new DeliveryKey(
            84,
            "INC102",
            1,
            "Bob"
        );

        send(key, "ALERT|83|INC102");
        send(second, "ALERT|84|INC102");

        timer.runPending();

        assertEquals(4, transport.sentMessages().size());
        assertEquals(2, acknowledgements.pendingCount());
        assertTrue(acknowledgements.acknowledge(key));
        assertTrue(acknowledgements.acknowledge(second));
    }

    @Test
    public void handlerCorrelatesEveryAcknowledgementField()
        throws Exception {

        send(key, "ALERT|83|INC102");
        AcknowledgementHandler handler =
            new AcknowledgementHandler(acknowledgements);

        ControlMessage stale = ControlMessage.of(
            MessageType.ALERT_ACK,
            "83",
            "INC102",
            "2",
            "Alice"
        );

        ControlMessage matching = ControlMessage.of(
            MessageType.ALERT_ACK,
            "83",
            "INC102",
            "1",
            "Alice"
        );

        assertFalse(handler.handle(stale));
        assertTrue(handler.handle(matching));
    }

    @Test
    public void shutdownCancelsPendingRetry() throws Exception {
        send(key, "ALERT|83|INC102");

        acknowledgements.close();
        timer.runPending();

        assertEquals(1, transport.sentMessages().size());
        assertEquals(0, acknowledgements.pendingCount());
    }

    private void send(DeliveryKey deliveryKey, String payload)
        throws Exception {

        acknowledgements.sendReliably(
            deliveryKey,
            new InetSocketAddress("127.0.0.1", 5001),
            payload.getBytes(StandardCharsets.UTF_8)
        );
    }
}