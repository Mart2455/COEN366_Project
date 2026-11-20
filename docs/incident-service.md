# Incident lifecycle service (issue #12)

## Construction and dispatch

Construct `IncidentService(store, registry, idGenerator, eventSink)` using
`IncidentStorePort`, `RegistryPort`, `IncidentIdGeneratorPort`, and
`IncidentEventSinkPort`. Tests inject fakes; no sibling implementation or real
socket is required.

The runtime dispatches `PUBLISH_ALERT` to `publish(request, sender)`, `UPDATE_ALERT`
to `update(request, sender)`, and `CLOSE_ALERT` to `close(request, sender)`.
Pass the actual UDP source endpoint, not a client-declared identity. The caller
must select the correct method for the decoded message type. The service validates
positive 64-bit request numbers, registration, publisher-capable roles, and fields.
Updates and closure require the original creator and an ACTIVE incident.

Responses are returned as `ControlMessage`; the service does not send UDP:

- Publish: `ALERT_ACCEPTED|RQ#|Incident_ID`.
- Update: `ALERT_UPDATED|RQ#|Incident_ID|Version`.
- Close: `ALERT_CLOSED|RQ#|Incident_ID`.
- Denial: `ALERT_DENIED|RQ#|Reason` for all three operations. Reusing this message
  for update and close is a team design choice; the PDF leaves their denial names
  unspecified.

Updates preserve category, location, attachment, and ownership, incrementing the
version. Closure preserves incident details and metadata, increments the version,
and stores CLOSED without deleting history. Conditional replacement prevents a
stale update/closure from overwriting a newer transition. A failed replacement
returns denial without emitting a lifecycle event.

## Shared contracts and recipient history

The INC additions are the ID-generator port, lifecycle event-sink port, and
`IncidentStorePort.recordRecipients/findRecipients` methods. The closure callback
is `onClosed(Incident incident, String reason)` so notification routing receives
the validated closure reason. All sink implementations must use that signature.

Notification routing records names selected for initial alerts and updates via
`store.recordRecipients(id, names)`. Sets accumulate without duplicates, independent
of current subscriptions. At closure, notification routing unions
`store.findRecipients(id)` with current matches and sends reliable ALL_CLEAR.
It owns recipient selection, delivery and ACK behavior; INC does not implement it.
History remains available after closure, including late recipient recording.
The fake store returns immutable history snapshots and retains them through
incident replacement. Production adapters must implement the same contract and
persist history with incident state.

## Integration responsibilities

The injected ID generator must atomically allocate `INC` plus a persisted increasing
number so IDs cannot repeat after restart. The service rejects collisions rather
than overwriting an incident. Test generators demonstrate distinct IDs within a
run; they do not prove production restart safety.

Attachment contents never enter this service; it stores a filename or NONE.
The registry must provide unambiguous active endpoint identities.

Lifecycle callbacks occur after successful store mutation. For crash-safe runtime
integration, use a queued event sink integrated with persistence. Persist accepted
state before sending the returned success response or distributing queued events.
A throwing callback is not rolled back by this service; reliable event recovery
requires runtime/outbox handling. The outer dispatcher must deduplicate requests
by sender and request number and replay original responses without invoking a
state transition twice. Networking, persistence adapters, replay handling, and
notification distribution remain runtime/sibling responsibilities.

## Verification

From `cenrs`, run `mvn -Dtest=IncidentServiceTest test` or `mvn test`.
Tests cover publication, metadata, role and ownership rejection, field and request
number validation, update and closure, recipient history, generated-ID collisions,
and distinct IDs supplied by a generator. Concurrent replacement tests use a
controlled interleaving: a competing state is committed after lookup but before
replacement. This verifies conflict handling without timing-dependent sleeps;
it is not a concurrent-load or restart test.
