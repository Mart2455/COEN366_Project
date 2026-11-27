package com.cenrs.client.control;

import com.cenrs.client.files.download.AttachmentInfo;
import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.Incident;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.SafetyStatus;
import com.cenrs.contract.Severity;
import com.cenrs.contract.StatusReport;
import com.cenrs.contract.Subscription;
import com.cenrs.contract.fake.FakePorts;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;
import com.cenrs.protocol.ProtocolException;
import com.cenrs.protocol.UdpMessageCodec;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ClientCommandControllerTest {
    private static final InetSocketAddress SERVER =
            new InetSocketAddress("127.0.0.1", 5000);

    private FakePorts.Transport transport;
    private UdpMessageCodec codec;
    private ClientControlState state;
    private ClientCommandController controller;

    @Before
    public void setUp() {
        transport = new FakePorts.Transport();
        codec = new UdpMessageCodec();
        state = new ClientControlState();
        controller = controller(Duration.ofSeconds(1));
    }

    @Test
    public void registrationUpdatesStateOnlyAfterAcceptedResponse()
            throws Exception {
        CompletableFuture<ControlMessage> future =
                controller.register("Alice", ClientRole.BOTH);

        assertEquals(
                ControlMessage.of(
                        MessageType.REGISTER,
                        "1",
                        "Alice",
                        "BOTH",
                        "127.0.0.1",
                        "5001",
                        "6001"
                ),
                sentMessage(0)
        );
        assertTrue(state.profile().isEmpty());

        ControlMessage accepted = ControlMessage.of(
                MessageType.REGISTERED,
                "1",
                "HALL,ALL",
                "FIRE,ALL"
        );

        assertTrue(controller.acceptResponse(accepted));
        assertEquals(accepted, future.get(1, TimeUnit.SECONDS));
        assertEquals("Alice", state.profile().orElseThrow().name());
        assertEquals(
                List.of(CampusLocation.HALL, CampusLocation.ALL),
                state.availableLocations()
        );
        assertEquals(
                List.of(AlertCategory.FIRE, AlertCategory.ALL),
                state.availableCategories()
        );
    }

    @Test
    public void deniedRegistrationLeavesStateEmpty() throws Exception {
        CompletableFuture<ControlMessage> future =
                controller.register("Alice", ClientRole.SUBSCRIBER);
        ControlMessage denied = ControlMessage.of(
                MessageType.REGISTER_DENIED,
                "1",
                "Name already exists"
        );

        controller.acceptResponse(denied);

        assertEquals(denied, future.get(1, TimeUnit.SECONDS));
        assertTrue(state.profile().isEmpty());
    }

    @Test
    public void subscriptionChangesStateOnlyAfterAcceptedResponse()
            throws Exception {
        registerLocally(ClientRole.SUBSCRIBER);

        CompletableFuture<ControlMessage> future = controller.subscribe(
                CampusLocation.HALL,
                AlertCategory.FIRE
        );

        assertTrue(state.subscriptions().isEmpty());
        ControlMessage accepted = ControlMessage.of(
                MessageType.SUBSCRIBED,
                "1"
        );
        controller.acceptResponse(accepted);

        assertEquals(accepted, future.get(1, TimeUnit.SECONDS));
        assertEquals(
                java.util.Set.of(
                        new Subscription(
                                CampusLocation.HALL,
                                AlertCategory.FIRE
                        )
                ),
                state.subscriptions()
        );
    }

    @Test
    public void publisherCannotSubscribe() throws Exception {
        registerLocally(ClientRole.PUBLISHER);

        expectException(
                IllegalStateException.class,
                () -> controller.subscribe(
                        CampusLocation.HALL,
                        AlertCategory.FIRE
                )
        );
        assertTrue(transport.sentMessages().isEmpty());
    }

    @Test
    public void unsubscribeAndDeregisterUpdateLocalState()
            throws Exception {
        registerLocally(ClientRole.SUBSCRIBER);
        Subscription subscription = new Subscription(
                CampusLocation.HALL,
                AlertCategory.FIRE
        );
        state.addSubscription(subscription);

        assertEquals(
                ControlMessage.of(
                        MessageType.UNSUBSCRIBE,
                        "1",
                        "HALL",
                        "FIRE"
                ),
                controller.unsubscribe(
                        CampusLocation.HALL,
                        AlertCategory.FIRE
                )
        );
        assertTrue(state.subscriptions().isEmpty());

        assertEquals(
                ControlMessage.of(
                        MessageType.DE_REGISTER,
                        "2",
                        "Alice"
                ),
                controller.deregister()
        );
        assertTrue(state.profile().isEmpty());
        assertTrue(state.availableLocations().isEmpty());
        assertTrue(state.availableCategories().isEmpty());
    }

    @Test
    public void acceptedPublicationStoresIncidentAndAttachment()
            throws Exception {
        registerLocally(ClientRole.PUBLISHER);

        CompletableFuture<ControlMessage> future = controller.publish(
                AlertCategory.FIRE,
                CampusLocation.HALL,
                Severity.CRITICAL,
                "Fire Alarm",
                "Evacuate the building",
                "evacuation_map.pdf"
        );

        assertEquals(
                ControlMessage.of(
                        MessageType.PUBLISH_ALERT,
                        "1",
                        "FIRE",
                        "HALL",
                        "CRITICAL",
                        "Fire Alarm",
                        "Evacuate the building",
                        "evacuation_map.pdf"
                ),
                sentMessage(0)
        );
        assertTrue(state.incidents().isEmpty());

        ControlMessage accepted = ControlMessage.of(
                MessageType.ALERT_ACCEPTED,
                "1",
                "INC102"
        );
        controller.acceptResponse(accepted);

        assertEquals(accepted, future.get(1, TimeUnit.SECONDS));
        assertEquals(sampleIncident(), state.incident("INC102").orElseThrow());
    }

    @Test
    public void deniedPublicationLeavesIncidentStateUnchanged()
            throws Exception {
        registerLocally(ClientRole.PUBLISHER);
        CompletableFuture<ControlMessage> future = controller.publish(
                AlertCategory.FIRE,
                CampusLocation.HALL,
                Severity.CRITICAL,
                "Fire Alarm",
                "Evacuate the building",
                "evacuation_map.pdf"
        );
        ControlMessage denied = ControlMessage.of(
                MessageType.ALERT_DENIED,
                "1",
                "Invalid incident fields"
        );

        controller.acceptResponse(denied);

        assertEquals(denied, future.get(1, TimeUnit.SECONDS));
        assertTrue(state.incidents().isEmpty());
    }

    @Test
    public void subscriberCannotPublish() throws Exception {
        registerLocally(ClientRole.SUBSCRIBER);

        expectException(
                IllegalStateException.class,
                () -> controller.publish(
                        AlertCategory.FIRE,
                        CampusLocation.HALL,
                        Severity.CRITICAL,
                        "Fire Alarm",
                        "Evacuate the building",
                        "NONE"
                )
        );

        assertTrue(transport.sentMessages().isEmpty());
    }

    @Test
    public void acceptedUpdateReplacesEditableIncidentFields()
            throws Exception {
        registerLocally(ClientRole.PUBLISHER);
        state.saveIncident(sampleIncident());

        CompletableFuture<ControlMessage> future = controller.update(
                "INC102",
                Severity.WARNING,
                "Fire Controlled",
                "Remain outside the building"
        );

        assertEquals(
                ControlMessage.of(
                        MessageType.UPDATE_ALERT,
                        "1",
                        "INC102",
                        "WARNING",
                        "Fire Controlled",
                        "Remain outside the building"
                ),
                sentMessage(0)
        );
        ControlMessage accepted = ControlMessage.of(
                MessageType.ALERT_UPDATED,
                "1",
                "INC102",
                "2"
        );
        controller.acceptResponse(accepted);

        assertEquals(accepted, future.get(1, TimeUnit.SECONDS));
        assertEquals(
                new Incident(
                        "INC102",
                        2,
                        AlertCategory.FIRE,
                        CampusLocation.HALL,
                        Severity.WARNING,
                        IncidentStatus.ACTIVE,
                        "Fire Controlled",
                        "Remain outside the building",
                        "evacuation_map.pdf",
                        "Alice"
                ),
                state.incident("INC102").orElseThrow()
        );
    }

    @Test
    public void deniedUpdatePreservesPreviousIncident()
            throws Exception {
        registerLocally(ClientRole.PUBLISHER);
        Incident original = sampleIncident();
        state.saveIncident(original);
        CompletableFuture<ControlMessage> future = controller.update(
                "INC102",
                Severity.WARNING,
                "Fire Controlled",
                "Remain outside the building"
        );
        ControlMessage denied = ControlMessage.of(
                MessageType.ALERT_DENIED,
                "1",
                "Only the creator may update this incident"
        );

        controller.acceptResponse(denied);

        assertEquals(denied, future.get(1, TimeUnit.SECONDS));
        assertEquals(original, state.incident("INC102").orElseThrow());
    }

    @Test
    public void acceptedClosureMarksIncidentClosed()
            throws Exception {
        registerLocally(ClientRole.PUBLISHER);
        state.saveIncident(sampleIncident());

        CompletableFuture<ControlMessage> future = controller.close(
                "INC102",
                "Incident resolved"
        );

        assertEquals(
                ControlMessage.of(
                        MessageType.CLOSE_ALERT,
                        "1",
                        "INC102",
                        "Incident resolved"
                ),
                sentMessage(0)
        );
        ControlMessage accepted = ControlMessage.of(
                MessageType.ALERT_CLOSED,
                "1",
                "INC102"
        );
        controller.acceptResponse(accepted);

        assertEquals(accepted, future.get(1, TimeUnit.SECONDS));
        Incident closed = state.incident("INC102").orElseThrow();
        assertEquals(2, closed.version());
        assertEquals(IncidentStatus.CLOSED, closed.status());
    }

    @Test
    public void acceptedStatusReportUpdatesLatestLocalStatus()
            throws Exception {
        registerLocally(ClientRole.SUBSCRIBER);
        state.saveIncident(sampleIncident());

        CompletableFuture<ControlMessage> future = controller.reportStatus(
                "INC102",
                SafetyStatus.SAFE
        );

        assertEquals(
                ControlMessage.of(
                        MessageType.STATUS_REPORT,
                        "1",
                        "INC102",
                        "Alice",
                        "SAFE"
                ),
                sentMessage(0)
        );
        assertTrue(state.statusReports().isEmpty());

        ControlMessage recorded = ControlMessage.of(
                MessageType.STATUS_RECORDED,
                "1"
        );
        controller.acceptResponse(recorded);

        assertEquals(recorded, future.get(1, TimeUnit.SECONDS));
        assertEquals(
                new StatusReport("INC102", "Alice", SafetyStatus.SAFE),
                state.statusReport("INC102").orElseThrow()
        );
    }

    @Test
    public void rejectedStatusReportPreservesPreviousStatus()
            throws Exception {
        registerLocally(ClientRole.SUBSCRIBER);
        state.saveIncident(sampleIncident());
        StatusReport previous = new StatusReport(
                "INC102",
                "Alice",
                SafetyStatus.NEED_HELP
        );
        state.saveStatusReport(previous);

        CompletableFuture<ControlMessage> future = controller.reportStatus(
                "INC102",
                SafetyStatus.SAFE
        );
        ControlMessage rejected = ControlMessage.of(
                MessageType.STATUS_REJECTED,
                "1",
                "Unknown incident"
        );
        controller.acceptResponse(rejected);

        assertEquals(rejected, future.get(1, TimeUnit.SECONDS));
        assertEquals(
                previous,
                state.statusReport("INC102").orElseThrow()
        );
    }

    @Test
    public void publisherCannotReportSubscriberStatus()
            throws Exception {
        registerLocally(ClientRole.PUBLISHER);
        state.saveIncident(sampleIncident());

        expectException(
                IllegalStateException.class,
                () -> controller.reportStatus(
                        "INC102",
                        SafetyStatus.SAFE
                )
        );

        assertTrue(transport.sentMessages().isEmpty());
    }

    @Test
    public void acceptedAttachmentDiscoveryStoresTcpEndpoint()
            throws Exception {
        registerLocally(ClientRole.SUBSCRIBER);
        state.saveIncident(sampleIncident());

        CompletableFuture<ControlMessage> future =
                controller.discoverAttachment(
                        "INC102",
                        "evacuation_map.pdf"
                );

        assertEquals(
                ControlMessage.of(
                        MessageType.ATTACH_REQ,
                        "1",
                        "INC102",
                        "evacuation_map.pdf"
                ),
                sentMessage(0)
        );
        assertTrue(state.attachmentInfos().isEmpty());

        ControlMessage infoResponse = ControlMessage.of(
                MessageType.ATTACH_INFO,
                "1",
                "INC102",
                "CampusSecurity",
                "192.168.1.15",
                "6100",
                "evacuation_map.pdf"
        );
        controller.acceptResponse(infoResponse);

        assertEquals(infoResponse, future.get(1, TimeUnit.SECONDS));
        assertEquals(
                new AttachmentInfo(
                        "1",
                        "INC102",
                        "CampusSecurity",
                        "192.168.1.15",
                        6100,
                        "evacuation_map.pdf"
                ),
                state.attachmentInfo("INC102").orElseThrow()
        );
    }

    @Test
    public void deniedAttachmentDiscoveryLeavesEndpointEmpty()
            throws Exception {
        registerLocally(ClientRole.SUBSCRIBER);
        state.saveIncident(sampleIncident());

        CompletableFuture<ControlMessage> future =
                controller.discoverAttachment(
                        "INC102",
                        "evacuation_map.pdf"
                );
        ControlMessage denied = ControlMessage.of(
                MessageType.ATTACH_DENIED,
                "1",
                "INC102",
                "Publisher unavailable"
        );
        controller.acceptResponse(denied);

        assertEquals(denied, future.get(1, TimeUnit.SECONDS));
        assertTrue(state.attachmentInfos().isEmpty());
    }

    @Test
    public void responsesAreCorrelatedByRequestNumber()
            throws Exception {
        registerLocally(ClientRole.SUBSCRIBER);
        CompletableFuture<ControlMessage> first = controller.subscribe(
                CampusLocation.HALL,
                AlertCategory.FIRE
        );
        CompletableFuture<ControlMessage> second = controller.subscribe(
                CampusLocation.EV,
                AlertCategory.SECURITY
        );

        ControlMessage secondResponse = ControlMessage.of(
                MessageType.SUBSCRIBED,
                "2"
        );
        ControlMessage firstResponse = ControlMessage.of(
                MessageType.SUBSCRIBED,
                "1"
        );

        controller.acceptResponse(secondResponse);
        controller.acceptResponse(firstResponse);

        assertEquals(firstResponse, first.get(1, TimeUnit.SECONDS));
        assertEquals(secondResponse, second.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void unexpectedResponseFailsTheMatchingCommand()
            throws Exception {
        CompletableFuture<ControlMessage> future =
                controller.register("Alice", ClientRole.SUBSCRIBER);

        assertTrue(controller.acceptResponse(ControlMessage.of(
                MessageType.STATUS_RECORDED,
                "1"
        )));

        ExecutionException exception = expectException(
                ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS)
        );
        assertTrue(exception.getCause() instanceof ProtocolException);
        assertTrue(state.profile().isEmpty());
    }

    @Test
    public void timeoutCompletesFutureWithoutBlockingCommandCall()
            throws Exception {
        controller = controller(Duration.ofMillis(30));

        CompletableFuture<ControlMessage> future =
                controller.register("Alice", ClientRole.SUBSCRIBER);

        assertFalse(future.isDone());
        ExecutionException exception = expectException(
                ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS)
        );
        assertTrue(exception.getCause() instanceof TimeoutException);
        assertFalse(controller.acceptResponse(ControlMessage.of(
                MessageType.REGISTERED,
                "1",
                "HALL",
                "FIRE"
        )));
    }

    private ClientCommandController controller(Duration timeout) {
        return new ClientCommandController(
                SERVER,
                "127.0.0.1",
                5001,
                6001,
                transport,
                codec,
                state,
                timeout
        );
    }

    private Incident sampleIncident() {
        return new Incident(
                "INC102",
                1,
                AlertCategory.FIRE,
                CampusLocation.HALL,
                Severity.CRITICAL,
                IncidentStatus.ACTIVE,
                "Fire Alarm",
                "Evacuate the building",
                "evacuation_map.pdf",
                "Alice"
        );
    }

    private void registerLocally(ClientRole role) {
        state.recordRegistration(
                new ClientProfile(
                        "Alice",
                        role,
                        "127.0.0.1",
                        5001,
                        6001
                ),
                List.of(CampusLocation.HALL, CampusLocation.EV),
                List.of(AlertCategory.FIRE, AlertCategory.SECURITY)
        );
    }

    private ControlMessage sentMessage(int index)
            throws ProtocolException {
        FakePorts.Transport.SentMessage sent =
                transport.sentMessages().get(index);
        assertEquals(SERVER, sent.destination());
        return codec.decode(sent.message());
    }

    private static <T extends Exception> T expectException(
            Class<T> expectedType,
            CheckedAction action
    ) throws Exception {
        try {
            action.run();
            fail("Expected " + expectedType.getSimpleName());
            return null;
        } catch (Exception exception) {
            if (!expectedType.isInstance(exception)) {
                throw exception;
            }

            return expectedType.cast(exception);
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
