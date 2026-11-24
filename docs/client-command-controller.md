# Client command controller

## Purpose

The client command controller creates CENRS UDP control messages, sends them through an injected transport, matches server responses to their original requests, and updates local client state only after successful responses.

The controller does not own a UDP socket, a receive loop, the command-line interface, or TCP file transfers. Those responsibilities belong to the client runtime and attachment-transfer modules.

## Main classes

### `ClientCommandController`

Creates and sends client control commands.

It supports:

- Registering and deregistering
- Subscribing and unsubscribing
- Publishing incidents
- Updating incidents
- Closing incidents
- Reporting user safety status
- Discovering attachment publisher endpoints

### `ClientControlState`

Stores the client’s current in-memory state:

- Registered client profile
- Available campus locations
- Available alert categories
- Subscriptions
- Known incidents and their versions
- Latest accepted safety status for each incident
- Discovered attachment publisher information

The state methods are synchronized because network responses and user commands may run on different threads.

## Command flow

A command normally follows this sequence:

1. Validate that the client is registered.
2. Validate that its role permits the command.
3. Validate command-specific input.
4. Generate a unique request number.
5. Create the protocol `ControlMessage`.
6. Register the request as pending.
7. Send the encoded message through `TransportPort`.
8. Return a `CompletableFuture` without blocking the receive loop.
9. Match the server response using the request number.
10. Update local state only when the response indicates success.

## Request correlation

Every outgoing request receives a positive request number.

For example:

```text
SUBSCRIBE|7|HALL|FIRE
```

The corresponding response must contain the same number:

```text
SUBSCRIBED|7
```

Pending requests are stored by request number. This allows responses to arrive in a different order from the commands that created them.

An unexpected response type completes the matching future exceptionally. A response arriving after its request timed out is ignored because that request is no longer pending.

## Timeouts

Commands that expect a server response return a `CompletableFuture<ControlMessage>`.

The future completes when:

- An expected success response arrives
- An expected denial response arrives
- An unexpected response produces a protocol error
- The configured response timeout expires

Waiting for a response does not freeze UDP reception.

## Registration and subscriptions

Registration sends:

```text
REGISTER|RQ#|Name|Role|IP|UDP_Port|TCP_Port
```

The local profile and server catalogues are stored only after `REGISTERED`.

Subscription sends:

```text
SUBSCRIBE|RQ#|Location|Category
```

The subscription is stored only after `SUBSCRIBED`.

Publisher-only clients cannot subscribe. Subscriber and `BOTH` clients can subscribe.

## Publisher incident commands

Publishing sends:

```text
PUBLISH_ALERT|RQ#|Category|Location|Severity|Title|Description|Attachment
```

The Emergency Server assigns the real incident ID. The client stores the new incident only after receiving `ALERT_ACCEPTED`.

Updating sends:

```text
UPDATE_ALERT|RQ#|Incident_ID|Severity|Title|Description
```

The controller requires the incident to be active and owned by the current publisher. The local incident is replaced only after `ALERT_UPDATED`.

Closing sends:

```text
CLOSE_ALERT|RQ#|Incident_ID|Reason
```

The local incident is marked `CLOSED` only after `ALERT_CLOSED`.

Denied publisher commands do not modify local incident state.

## Safety-status reporting

A subscriber reports its latest status using:

```text
STATUS_REPORT|RQ#|Incident_ID|Name|Status
```

Valid statuses are:

- `SAFE`
- `NEED_HELP`
- `LEFT_AREA`

The latest local status is replaced only after `STATUS_RECORDED`. A `STATUS_REJECTED` response preserves the previously accepted status.

## Attachment discovery

A subscriber asks the Emergency Server for an attachment publisher using:

```text
ATTACH_REQ|RQ#|Incident_ID|File_Name
```

An accepted `ATTACH_INFO` response supplies:

- Incident ID
- Publisher name
- Publisher IP address
- Publisher TCP port
- Filename

The controller validates and stores this information as an `AttachmentInfo` object.

Attachment discovery uses UDP. It does not transfer file bytes.

## UDP and TCP boundary

The command controller uses UDP for control messages.

```text
Subscriber
    -- UDP ATTACH_REQ --> Emergency Server
    <-- UDP ATTACH_INFO --
```

After discovery, the existing attachment downloader connects directly to the publisher through TCP:

```text
Subscriber
    -- TCP GET_ATTACHMENT --> Publisher
    <-- TCP header and file bytes --
```

The Emergency Server provides the publisher endpoint but does not carry attachment contents.

## Local state versus persistent state

`ClientControlState` is temporary in-memory client state. It is cleared when the client deregisters and is not a database.

Durable server recovery is a separate server-persistence responsibility. The command controller does not write server database or snapshot files.

## Testing

Run the complete project test suite from the repository root:

```bash
mvn -f cenrs/pom.xml clean test
```

The completed Issue #20 implementation is expected to produce:

```text
Tests run: 115, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The controller tests use a fake transport. They verify exact protocol messages, request correlation, state changes, denials, role restrictions, out-of-order responses, and timeouts without requiring a running Emergency Server.