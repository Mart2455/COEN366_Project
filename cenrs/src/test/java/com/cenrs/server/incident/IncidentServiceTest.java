package com.cenrs.server.incident;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Before;
import org.junit.Test;

import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.Incident;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.Severity;
import com.cenrs.contract.fake.FakePorts;
import com.cenrs.contract.port.IncidentEventSinkPort;
import com.cenrs.contract.port.IncidentIdGeneratorPort;
import com.cenrs.contract.port.IncidentStorePort;
import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;

public class IncidentServiceTest 
{
    private FakePorts.Registry registry;
    private FakePorts.Incidents store;
    private List<Incident> createdEvents;
    private List<Incident> updatedEvents;
    private List<Incident> closedEvents;
    private List<String> closureReasons;
    private IncidentService service;
    private ControlMessage request;
    private InetSocketAddress sender;

    @Before
    public void setUp() 
    {
        registry = new FakePorts.Registry();
        store = new FakePorts.Incidents();
        createdEvents = new ArrayList<>();
        updatedEvents = new ArrayList<>();
        closedEvents = new ArrayList<>();
        closureReasons = new ArrayList<>();

        IncidentIdGeneratorPort idGenerator = () -> "INC1";

        IncidentEventSinkPort eventSink = new IncidentEventSinkPort() {
            @Override
            public void onCreated(Incident incident) 
            {
                createdEvents.add(incident);
            }

            @Override
            public void onUpdated(Incident incident) 
            {
                updatedEvents.add(incident);
            }

            @Override
            public void onClosed(Incident incident, String reason) 
            {
                closedEvents.add(incident);
                closureReasons.add(reason);
            }
        };

        service = new IncidentService(
                store, registry, idGenerator, eventSink
        );

        request = ControlMessage.of(
                MessageType.PUBLISH_ALERT,
                "1", "FIRE", "HALL", "CRITICAL",
                "Fire Alarm", "Evacuate the Building", "NONE"
        );

        sender = new InetSocketAddress("127.0.0.1", 5001);
    }

    @Test
    public void validPublisherCreatesActiveIncident() 
    {
        // Arrange: register the publisher in the fresh registry.
        registry.addIfAbsent(new ClientProfile(
                "ALI", ClientRole.PUBLISHER, "127.0.0.1", 5001, 6001
        ));

        // Act
        ControlMessage response = service.publish(request, sender);

        // Assert: verify the acceptance response.
        assertEquals(MessageType.ALERT_ACCEPTED, response.type());
        assertEquals("1", response.field(0));
        assertEquals("INC1", response.field(1));

        // Verify the incident was stored.
        var saved = store.findById("INC1");
        assertTrue(saved.isPresent());
        Incident incident = saved.get();

        assertEquals(IncidentStatus.ACTIVE, incident.status());
        assertEquals(1L, incident.version());
        assertEquals("ALI", incident.publisherName());
        assertEquals("NONE", incident.attachment());

        // Verify the created event contains the stored incident.
        assertEquals(1, createdEvents.size());
        assertEquals(incident, createdEvents.get(0));
    }

    @Test
    public void unregisteredClientCannotPublish() {
        // Arrange: setUp leaves the registry empty.

        // Act
        ControlMessage response = service.publish(request, sender);

        // Assert
        assertEquals(MessageType.ALERT_DENIED, response.type());
        assertEquals("1", response.field(0));
        assertTrue(store.findActive().isEmpty());
        assertTrue(createdEvents.isEmpty());
    }

    @Test
    public void subscriberCannotPublish() 
    {
        registry.addIfAbsent(new ClientProfile(
                "ALI", ClientRole.SUBSCRIBER,
                "127.0.0.1", 5001, 6001
        ));

        ControlMessage response = service.publish(request, sender);

        // Check if alert is indeed denied
        assertEquals(MessageType.ALERT_DENIED, response.type());
        
        assertEquals("1", response.field(0));
        assertTrue(store.findActive().isEmpty());
        assertTrue(createdEvents.isEmpty());
    }

    @Test
    public void invalidSeverityIsDenied() 
    {
        registry.addIfAbsent(new ClientProfile(
                "ALI", ClientRole.PUBLISHER,
                "127.0.0.1", 5001, 6001
        ));

        ControlMessage invalidRequest = ControlMessage.of(
                MessageType.PUBLISH_ALERT,
                "1", "FIRE", "HALL", "INVALID",
                "Fire Alarm", "Evacuate the Building", "NONE"
        );

        ControlMessage response = service.publish(invalidRequest, sender);

        assertEquals(MessageType.ALERT_DENIED, response.type());
        assertEquals("1", response.field(0));
        assertTrue(store.findActive().isEmpty());
        assertTrue(createdEvents.isEmpty());
    }

    @Test
    public void bothRoleCanPublishWithAttachment() 
    {
        registry.addIfAbsent(new ClientProfile(
                "ALI", ClientRole.BOTH,
                "127.0.0.1", 5001, 6001
        ));

        ControlMessage attachmentRequest = ControlMessage.of(
                MessageType.PUBLISH_ALERT,
                "1", "FIRE", "HALL", "CRITICAL",
                "Fire Alarm", "Evacuate the Building",
                "evacuation_map.pdf"
        );

        ControlMessage response = service.publish(attachmentRequest, sender);

        assertEquals(MessageType.ALERT_ACCEPTED, response.type());

        Incident incident = store.findById("INC1").orElseThrow();
        assertEquals("evacuation_map.pdf", incident.attachment());
        assertEquals("ALI", incident.publisherName());
        assertEquals(1, createdEvents.size());
        assertEquals(incident, createdEvents.get(0));
    }

    

    @Test
    public void creatorCanUpdateActiveIncident() {
        // Arrange: register the creator and publish the original incident.
        registry.addIfAbsent(new ClientProfile(
                "ALI", ClientRole.PUBLISHER,
                "127.0.0.1", 5001, 6001
        ));
        service.publish(request, sender);
        Incident original = store.findById("INC1").orElseThrow();

        ControlMessage updateRequest = ControlMessage.of(
                MessageType.UPDATE_ALERT,
                "2", "INC1", "WARNING",
                "Fire controlled", "Remain outside"
        );

        // Act: update the incident from its creator's endpoint.
        ControlMessage response = service.update(updateRequest, sender);

        // Verify acceptance includes the request number, ID, and new version.
        assertEquals(MessageType.ALERT_UPDATED, response.type());
        assertEquals("2", response.field(0));
        assertEquals("INC1", response.field(1));
        assertEquals("2", response.field(2));

        Incident updated = store.findById("INC1").orElseThrow();

        // Verify the stored version increased and new details were applied.
        assertEquals(2L, updated.version());
        assertEquals(Severity.WARNING, updated.severity());
        assertEquals("Fire controlled", updated.title());
        assertEquals("Remain outside", updated.description());

        // Verify identity, metadata, ownership, and active status were preserved.
        assertEquals(original.incidentId(), updated.incidentId());
        assertEquals(original.category(), updated.category());
        assertEquals(original.location(), updated.location());
        assertEquals(original.attachment(), updated.attachment());
        assertEquals(original.publisherName(), updated.publisherName());
        assertEquals(IncidentStatus.ACTIVE, updated.status());

        // Verify exactly one update event contains the newly stored incident.
        assertEquals(1, updatedEvents.size());
        assertEquals(updated, updatedEvents.get(0));
    }

    @Test
    public void differentPublisherCannotUpdateIncident() 
    {
        Incident original = publishOriginalIncident();
        registry.addIfAbsent(new ClientProfile(
                "BOB", ClientRole.PUBLISHER, "127.0.0.1", 5002, 6002
        ));
        ControlMessage updateRequest = ControlMessage.of(
                MessageType.UPDATE_ALERT,
                "2", "INC1", "WARNING", "Fire controlled", "Remain outside"
        );

        ControlMessage response = service.update(
                updateRequest, new InetSocketAddress("127.0.0.1", 5002)
        );

        // Verify a registered publisher cannot modify another creator's incident.
        assertDeniedUpdateLeavesIncidentUnchanged(response, original);
        assertEquals("Only the creator may update this incident", response.field(1));
    }

    @Test
    public void unknownIncidentCannotBeUpdated() 
    {
        Incident original = publishOriginalIncident();
        ControlMessage updateRequest = ControlMessage.of(
                MessageType.UPDATE_ALERT,
                "2", "MISSING", "WARNING", "Fire controlled", "Remain outside"
        );

        ControlMessage response = service.update(updateRequest, sender);

        // Verify rejection does not modify the existing incident or emit an update.
        assertDeniedUpdateLeavesIncidentUnchanged(response, original);
        assertEquals("Unknown incident", response.field(1));

        // Verify an update cannot create the unknown incident.
        assertTrue(store.findById("MISSING").isEmpty());
    }

    @Test
    public void closedIncidentCannotBeUpdated() 
    {
        Incident original = publishOriginalIncident();
        // Seed a closed incident directly; closure implementation is not required.
        Incident closed = new Incident(
                original.incidentId(), 2,
                original.category(), original.location(), original.severity(),
                IncidentStatus.CLOSED, original.title(), original.description(),
                original.attachment(), original.publisherName()
        );
        assertTrue("Test fixture must contain the closed incident",
                store.replaceIfCurrent(original, closed));
        ControlMessage updateRequest = ControlMessage.of(
                MessageType.UPDATE_ALERT,
                "2", "INC1", "WARNING", "Fire controlled", "Remain outside"
        );

        ControlMessage response = service.update(updateRequest, sender);

        // Verify even the creator cannot edit a closed incident or emit an update.
        assertDeniedUpdateLeavesIncidentUnchanged(response, closed);
        assertEquals("Incident is closed", response.field(1));
    }

    @Test
    public void invalidUpdateSeverityIsDenied() 
    {
        Incident original = publishOriginalIncident();
        ControlMessage updateRequest = ControlMessage.of(
                MessageType.UPDATE_ALERT,
                "2", "INC1", "INVALID", "Fire controlled", "Remain outside"
        );

        ControlMessage response = service.update(updateRequest, sender);

        // Verify invalid input is denied without changing state or emitting an update.
        assertDeniedUpdateLeavesIncidentUnchanged(response, original);
        assertEquals("Invalid incident fields", response.field(1));
    }

    @Test
    public void blankUpdateTitleIsDenied() 
    {
        Incident original = publishOriginalIncident();
        ControlMessage updateRequest = ControlMessage.of(
                MessageType.UPDATE_ALERT,
                "2", "INC1", "WARNING", "   ", "Remain outside"
        );

        ControlMessage response = service.update(updateRequest, sender);

        // Verify invalid input is denied without changing state or emitting an update.
        assertDeniedUpdateLeavesIncidentUnchanged(response, original);
        assertEquals("Invalid incident fields", response.field(1));
    }

    @Test
    public void oversizedUpdateDescriptionIsDenied() 
    {
        Incident original = publishOriginalIncident();
        ControlMessage updateRequest = ControlMessage.of(
                MessageType.UPDATE_ALERT,
                "2", "INC1", "WARNING", "Fire controlled", "x".repeat(1025)
        );

        ControlMessage response = service.update(updateRequest, sender);

        // Verify invalid input is denied without changing state or emitting an update.
        assertDeniedUpdateLeavesIncidentUnchanged(response, original);
        assertEquals("Invalid incident fields", response.field(1));
    }

    private Incident publishOriginalIncident() 
    {
        registry.addIfAbsent(new ClientProfile(
                "ALI", ClientRole.PUBLISHER, "127.0.0.1", 5001, 6001
        ));
        ControlMessage response = service.publish(request, sender);
        assertEquals("Test fixture publication must succeed",
                MessageType.ALERT_ACCEPTED, response.type());
        return store.findById("INC1").orElseThrow();
    }

    private void assertDeniedUpdateLeavesIncidentUnchanged(ControlMessage response, 
                                                           Incident expected) 
    {
        // Verify denial is correlated with the original update request.
        assertEquals(MessageType.ALERT_DENIED, response.type());
        assertEquals("2", response.field(0));

        // Verify all stored fields, including version and status, remain unchanged.
        assertEquals(expected, store.findById(expected.incidentId()).orElseThrow());

        // Verify rejected updates produce no downstream notification event.
        assertTrue(updatedEvents.isEmpty());
    }

    @Test
    public void creatorCanCloseActiveIncident() 
    {
        Incident original = publishOriginalIncident();
        ControlMessage closeRequest = ControlMessage.of(
                MessageType.CLOSE_ALERT, "2", "INC1", "Building reopened"
        );

        ControlMessage response = service.close(closeRequest, sender);

        // Verify closure acceptance includes the original request number and ID.
        assertEquals(MessageType.ALERT_CLOSED, response.type());
        assertEquals("2", response.field(0));
        assertEquals("INC1", response.field(1));

        Incident closed = store.findById("INC1").orElseThrow();
        // Verify closure increments the version, preserves all details, and stores CLOSED.
        Incident expected = new Incident(
                original.incidentId(), 2, original.category(), original.location(),
                original.severity(), IncidentStatus.CLOSED, original.title(),
                original.description(), original.attachment(), original.publisherName()
        );
        assertEquals(expected, closed);
        // Verify closed history remains stored but is excluded from active incidents.
        assertTrue(store.findActive().isEmpty());
        // Verify notification routing receives exactly one closure with its reason.
        assertEquals(List.of(closed), closedEvents);
        assertEquals(List.of("Building reopened"), closureReasons);
        // Verify closure is not also reported as an update.
        assertTrue(updatedEvents.isEmpty());
    }

    @Test
    public void differentPublisherCannotCloseIncident() {
        Incident original = publishOriginalIncident();
        registry.addIfAbsent(new ClientProfile(
                "BOB", ClientRole.PUBLISHER, "127.0.0.1", 5002, 6002
        ));
        ControlMessage response = service.close(
                ControlMessage.of(MessageType.CLOSE_ALERT, "2", "INC1", "Resolved"),
                new InetSocketAddress("127.0.0.1", 5002)
        );
        assertDeniedClosureLeavesIncidentUnchanged(response, original);
        // Verify the registered publisher is rejected specifically for ownership.
        assertEquals("Only the creator may close this incident", response.field(1));
    }

    @Test
    public void unregisteredClientCannotCloseIncident() {
        Incident original = publishOriginalIncident();
        ControlMessage response = service.close(
                ControlMessage.of(MessageType.CLOSE_ALERT, "2", "INC1", "Resolved"),
                new InetSocketAddress("127.0.0.1", 5999)
        );
        assertDeniedClosureLeavesIncidentUnchanged(response, original);
        // Verify an unknown endpoint is rejected before any closure is accepted.
        assertEquals("Client is not registered", response.field(1));
    }

    @Test
    public void subscriberCannotCloseIncident() {
        Incident original = publishOriginalIncident();
        registry.addIfAbsent(new ClientProfile(
                "BOB", ClientRole.SUBSCRIBER, "127.0.0.1", 5002, 6002
        ));
        ControlMessage response = service.close(
                ControlMessage.of(MessageType.CLOSE_ALERT, "2", "INC1", "Resolved"),
                new InetSocketAddress("127.0.0.1", 5002)
        );
        assertDeniedClosureLeavesIncidentUnchanged(response, original);
        // Verify subscriber role cannot close an incident.
        assertEquals("Unauthorized role", response.field(1));
    }

    @Test
    public void unknownIncidentCannotBeClosed() {
        Incident original = publishOriginalIncident();
        ControlMessage response = service.close(
                ControlMessage.of(MessageType.CLOSE_ALERT, "2", "MISSING", "Resolved"),
                sender
        );
        assertDeniedClosureLeavesIncidentUnchanged(response, original);
        // Verify an unknown ID is rejected without creating a closed record.
        assertEquals("Unknown incident", response.field(1));
        assertTrue(store.findById("MISSING").isEmpty());
    }

    @Test
    public void alreadyClosedIncidentCannotBeClosedAgain() 
    {
        publishOriginalIncident();
        ControlMessage closeRequest = ControlMessage.of(
                MessageType.CLOSE_ALERT, "2", "INC1", "Resolved"
        );
        service.close(closeRequest, sender);
        Incident closed = store.findById("INC1").orElseThrow();
        closedEvents.clear();
        closureReasons.clear();

        ControlMessage response = service.close(closeRequest, sender);

        assertDeniedClosureLeavesIncidentUnchanged(response, closed);
        // Verify a second closure does not increment the version or emit another event.
        assertEquals("Incident is already closed", response.field(1));
    }

    @Test
    public void invalidClosureReasonsAreDenied() 
    {
        Incident original = publishOriginalIncident();
        String[] invalidReasons = {
                "", "   ", "Bad|reason", "Bad\nreason", "Bad\rreason",
                "x".repeat(257), "é".repeat(129)
        };
        for (String reason : invalidReasons) {
            ControlMessage response = service.close(
                    ControlMessage.of(MessageType.CLOSE_ALERT, "2", "INC1", reason),
                    sender
            );
            assertDeniedClosureLeavesIncidentUnchanged(response, original);
            // Verify reason validation covers whitespace, delimiters, and UTF-8 byte size.
            assertEquals("Invalid closure reason", response.field(1));
        }
    }

    private void assertDeniedClosureLeavesIncidentUnchanged(ControlMessage response, Incident expected) 
    {
        // Verify denial is correlated with the closure request.
        assertEquals(MessageType.ALERT_DENIED, response.type());
        assertEquals("2", response.field(0));
        // Verify rejection preserves all incident fields, including version and status.
        assertEquals(expected, store.findById(expected.incidentId()).orElseThrow());
        // Verify rejection emits no closure event or reason.
        assertTrue(closedEvents.isEmpty());
        assertTrue(closureReasons.isEmpty());
    }


    @Test
    public void closingIncidentPreservesRecipientHistory() 
    {
        publishOriginalIncident();

        // Record two recipients, then add another with an overlapping name.
        store.recordRecipients("INC1", java.util.Set.of("Alice", "Bob"));
        store.recordRecipients("INC1", java.util.Set.of("Bob", "Carol"));

        ControlMessage response = service.close(
                ControlMessage.of(
                        MessageType.CLOSE_ALERT,
                        "2", "INC1", "Building reopened"
                        ),
                        sender
                );

        // Verify the creator successfully closed the incident.
        assertEquals(MessageType.ALERT_CLOSED, response.type());

        // Verify all previous recipients remain recorded without duplicates.
        assertEquals(
                java.util.Set.of("Alice", "Bob", "Carol"),
                store.findRecipients("INC1")
        );

                // Verify the incident remains stored as CLOSED.
        assertEquals(
                IncidentStatus.CLOSED,
                store.findById("INC1").orElseThrow().status()
        );
     }

    @Test
    public void invalidUpdateRequestNumbersAreDenied() {
        Incident original = publishOriginalIncident();

        for (String number : List.of("0", "-1", "abc", "9223372036854775808")) 
        {
            ControlMessage invalidRequest = ControlMessage.of(
                    MessageType.UPDATE_ALERT,
                    number, "INC1", "WARNING",
                    "Fire controlled", "Remain outside"
            );

            ControlMessage response = service.update(invalidRequest, sender);

            // Verify invalid request numbers return a correlated denial.
            assertEquals(MessageType.ALERT_DENIED, response.type());
            assertEquals(number, response.field(0));
            assertEquals("Invalid request number", response.field(1));

            // Verify rejection preserves every stored field and emits no update.
            assertEquals(original, store.findById("INC1").orElseThrow());
            assertTrue(updatedEvents.isEmpty());
        }
    }

    @Test
    public void invalidPublishRequestNumbersAreDenied() 
    {
        registry.addIfAbsent(new ClientProfile(
                "ALI", ClientRole.PUBLISHER, "127.0.0.1", 5001, 6001
        ));

        for (String number : List.of(
                "0", "-1", "abc", "9223372036854775808"
        )) {
            ControlMessage invalidRequest = ControlMessage.of(
                    MessageType.PUBLISH_ALERT,
                    number, "FIRE", "HALL", "CRITICAL",
                    "Fire Alarm", "Evacuate the Building", "NONE"
            );

            ControlMessage response = service.publish(invalidRequest, sender);

            // Verify invalid request numbers return a correlated denial.
            assertEquals(MessageType.ALERT_DENIED, response.type());
            assertEquals(number, response.field(0));
            assertEquals("Invalid request number", response.field(1));

            // Verify rejection stores no incident and emits no lifecycle events.
            assertTrue(store.findActive().isEmpty());
            assertTrue(store.findById("INC1").isEmpty());
            assertTrue(createdEvents.isEmpty());
            assertTrue(updatedEvents.isEmpty());
            assertTrue(closedEvents.isEmpty());
        }
    }

    @Test
    public void multiplePublicationsUseDifferentIncidentIds() 
    {
        registry.addIfAbsent(new ClientProfile(
                "ALI", ClientRole.PUBLISHER, "127.0.0.1", 5001, 6001
        ));
        AtomicLong counter = new AtomicLong(1);
        service = recordingService(store, () -> "INC" + counter.getAndIncrement());

        ControlMessage first = service.publish(request, sender);
        ControlMessage second = service.publish(ControlMessage.of(
                MessageType.PUBLISH_ALERT,
                "2", "FIRE", "HALL", "WARNING",
                "Second incident", "Stay outside", "map.pdf"
        ), sender);

        // Verify publications use separate IDs supplied by the injected generator.
        assertEquals(MessageType.ALERT_ACCEPTED, first.type());
        assertEquals(MessageType.ALERT_ACCEPTED, second.type());
        assertEquals("INC1", first.field(1));
        assertEquals("INC2", second.field(1));
        // Verify both incidents retain their own attachment metadata.
        assertEquals(2, store.findActive().size());
        assertEquals("NONE", store.findById("INC1").orElseThrow().attachment());
        assertEquals("map.pdf", store.findById("INC2").orElseThrow().attachment());
        // Verify one created event was emitted for each accepted publication.
        assertEquals(2, createdEvents.size());
    }

    @Test
    public void duplicateGeneratedIdDoesNotOverwriteIncident() 
    {
        Incident original = publishOriginalIncident();
        ControlMessage response = service.publish(ControlMessage.of(
                MessageType.PUBLISH_ALERT,
                "2", "FIRE", "HALL", "WARNING",
                "Second incident", "Different description", "map.pdf"
        ), sender);

        // The fixture generator returns INC1 again; the existing incident must win.
        assertEquals(MessageType.ALERT_DENIED, response.type());
        assertEquals("2", response.field(0));
        assertEquals(original, store.findById("INC1").orElseThrow());
        assertEquals(1, store.findActive().size());
        // Verify the rejected collision produces no second creation event.
        assertEquals(1, createdEvents.size());
    }

    @Test
    public void recipientHistorySurvivesUpdateAndClosure() 
    {
        publishOriginalIncident();
        store.recordRecipients("INC1", Set.of("Alice", "Bob"));
        Set<String> earlierSnapshot = store.findRecipients("INC1");

        ControlMessage updateResponse = service.update(ControlMessage.of(
                MessageType.UPDATE_ALERT,
                "2", "INC1", "WARNING", "Fire controlled", "Remain outside"
        ), sender);

        // Verify updating the incident does not erase prior recipients.
        assertEquals(MessageType.ALERT_UPDATED, updateResponse.type());
        assertEquals(Set.of("Alice", "Bob"), store.findRecipients("INC1"));
        store.recordRecipients("INC1", Set.of("Bob", "Carol"));

        ControlMessage closeResponse = service.close(ControlMessage.of(
                MessageType.CLOSE_ALERT, "3", "INC1", "Building reopened"
        ), sender);

        // Verify closure retains the union of initial and update recipients.
        assertEquals(MessageType.ALERT_CLOSED, closeResponse.type());
        assertEquals(Set.of("Alice", "Bob", "Carol"), store.findRecipients("INC1"));
        // Verify an earlier returned snapshot is not changed by later recording.
        assertEquals(Set.of("Alice", "Bob"), earlierSnapshot);
        // Verify closure after an update advances to version three.
        Incident closed = store.findById("INC1").orElseThrow();
        assertEquals(IncidentStatus.CLOSED, closed.status());
        assertEquals(3L, closed.version());
        assertEquals("ALI", closed.publisherName());
    }

    @Test
    public void concurrentUpdateIsNotOverwritten() 
    {
        assertCompetingChangeIsPreserved(false);
    }

    @Test
    public void concurrentChangePreventsStaleClosure() 
    {
        assertCompetingChangeIsPreserved(true);
    }

    private void assertCompetingChangeIsPreserved(boolean closing) 
    {
        Incident original = publishOriginalIncident();
        Incident winner = new Incident(
                original.incidentId(), 2,
                original.category(), original.location(), Severity.INFO,
                IncidentStatus.ACTIVE, "Newer state", "Another operation committed",
                original.attachment(), original.publisherName()
        );
        store.recordRecipients("INC1", Set.of("Alice"));
        service = recordingService(storeWithCompetingReplacement(winner), () -> "INC2");

        ControlMessage response = closing
                ? service.close(ControlMessage.of(
                        MessageType.CLOSE_ALERT, "2", "INC1", "Resolved"
                ), sender)
                : service.update(ControlMessage.of(
                        MessageType.UPDATE_ALERT, "2", "INC1", "WARNING",
                        "Stale update", "Old information"
                ), sender);

        // Verify a stale operation is denied and cannot overwrite the newer state.
        assertEquals(MessageType.ALERT_DENIED, response.type());
        assertEquals("2", response.field(0));
        assertEquals("Incident changed; retry with a new request", response.field(1));
        assertEquals(winner, store.findById("INC1").orElseThrow());
        // Verify recipient history also survives the competing replacement.
        assertEquals(Set.of("Alice"), store.findRecipients("INC1"));
        // Verify unsuccessful transitions do not emit downstream lifecycle events.
        assertTrue(updatedEvents.isEmpty());
        assertTrue(closedEvents.isEmpty());
        assertTrue(closureReasons.isEmpty());
    }

    private IncidentStorePort storeWithCompetingReplacement(Incident winner) 
    {
        return new IncidentStorePort() 
        {
            @Override
            public boolean create(Incident incident) 
            {
                return store.create(incident);
            }

            @Override
            public Optional<Incident> findById(String id)
            {
                return store.findById(id);
            }

            @Override
            public List<Incident> findActive() 
            {
                return store.findActive();
            }

            @Override
            public void recordRecipients(String id, Set<String> names) 
            {
                store.recordRecipients(id, names);
            }

            @Override
            public Set<String> findRecipients(String id) 
            {
                return store.findRecipients(id);
            }

            @Override
            public boolean replaceIfCurrent(Incident current, Incident replacement) 
            {
                // Deterministically commit a competing operation after lookup.
                assertTrue("Competing change must commit first",
                        store.replaceIfCurrent(current, winner));
                return store.replaceIfCurrent(current, replacement);
            }
        };
    }

    private IncidentService recordingService(IncidentStorePort incidentStore, IncidentIdGeneratorPort generator) 
    {
        return new IncidentService(incidentStore, registry, generator, new IncidentEventSinkPort() 
        {
                    @Override
                    public void onCreated(Incident incident) 
                    {
                        createdEvents.add(incident);
                    }

                    @Override
                    public void onUpdated(Incident incident) 
                    {
                        updatedEvents.add(incident);
                    }

                    @Override
                    public void onClosed(Incident incident, String reason) 
                    {
                        closedEvents.add(incident);
                        closureReasons.add(reason);
                    }
                });
        }
}
