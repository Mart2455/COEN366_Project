# Persistence and Crash Recovery

The Emergency Server saves its whole state to one UTF-8 text snapshot (default `data/cenrs-state.txt`).

## When it is saved

State-changing requests are applied and the snapshot is saved before the success response is sent.

`FilePersistence` writes the snapshot to a temporary file first, then atomically replaces the previous snapshot. If the server crashes while writing, the old complete snapshot is still there and the incomplete temporary file is ignored.

## Snapshot format

The first line is `CENRS-STATE|2`. Each following line is one record, with fields separated by `|`:

```text
CLIENT|Name|Role|IP_Address|UDP_Port|TCP_Port
SUBSCRIPTION|Name|Location|Category
NEXT_INCIDENT|Number
INCIDENT|Incident_ID|Version|Category|Location|Severity|Status|Title|Description|Attachment|Publisher_Name
RECIPIENT|Incident_ID|Name
STATUS|Incident_ID|Name|Safety_Status
DELIVERY|RQ#|Incident_ID|Version|Name|Delivery_Status|Attempts|Updated_Epoch_Millis
```

Fields can never contain `|` or a newline, because the contract model rejects them.

Snapshots written by Phase One (`CENRS-STATE|1`, clients and subscriptions only) can still be restored.

## What is recovered

- user profiles and subscriptions
- active and closed incidents, with version, status, attachment name and publisher
- the recipients of each incident, so `ALL_CLEAR` still reaches them
- the latest safety status of each client for each incident
- the delivery status of every alert sent to each client
- the next incident number

## Restore rules

- The whole file is read and validated before anything is applied. An invalid snapshot does not change the live state.
- Restored clients are inactive until they register again. The first valid `REGISTER` for that name reactivates the profile and updates its endpoint. A second registration for an active name is denied.
- The incident counter is moved past the highest restored incident number, so an `Incident_ID` is never reused.
- Retransmission timers do not survive a crash. A delivery that was still `PENDING` is restored as `UNCONFIRMED`. The client can recover the current state using `GET_ACTIVE_ALERTS`.
