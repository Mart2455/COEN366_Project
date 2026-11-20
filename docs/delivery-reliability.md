# Delivery reliability

Issue #14 owns acknowledgement tracking and retransmission. Routing remains
separate: the routing module selects recipients and builds each notification.

For a CRITICAL ALERT, a CRITICAL ALERT_UPDATE, or every ALL_CLEAR, routing calls
AcknowledgementScheduler.sendReliably with:

- the outgoing request number;
- incident ID and version;
- registered client name and UDP endpoint;
- the already encoded notification bytes.

The scheduler sends immediately and starts the configurable timeout. A valid
ALERT_ACK must match the request number, incident ID, version, and client name.
It cancels the timer and records CONFIRMED.

Without a matching ACK, the same request number and payload are sent again.
There are exactly three total attempts. After the final timeout, the delivery
is recorded as UNCONFIRMED.

INFO and WARNING notifications are sent directly unless the message is
ALL_CLEAR. Timers are temporary and are cancelled during server shutdown.