# User safety status

`StatusService` accepts the latest safety status reported by a registered
subscriber for an active incident. The UDP sender must match the client name in
the request.

Accepted values are `SAFE`, `NEED_HELP`, and `LEFT_AREA`. A new report replaces
the same client's previous report for that incident. Reports for unknown or
closed incidents are rejected.

The service returns `STATUS_RECORDED` or `STATUS_REJECTED`. The runtime remains
responsible for sending that response to the client.

## Persistence

The current implementation stores statuses in memory. Issue #18 should provide
a durable `StatusStorePort` implementation that saves and restores the latest
report for each client and incident.

The persistent implementation will be injected instead of
`InMemoryStatusStore`. `StatusService` should not need to change.