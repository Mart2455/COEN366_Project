# CENRS Protocol

This document defines the messages exchanged between CENRS clients and the Emergency Server.

## Message format

- Control messages are UTF-8 text.
- Each UDP datagram contains one complete message.
- Fields are separated using `|`.
- Spaces are allowed inside fields.
- A field cannot contain `|` or a line break.
- Message names, roles, locations, and categories are uppercase.

A response uses the same `RQ#` as its request. This allows the sender to match a response with the correct request.

## Registration

A client registers with the server using:

```text
REGISTER|RQ#|Name|Role|IP_Address|UDP_Port|TCP_Port
```

Example:

```text
REGISTER|01|Alice|SUBSCRIBER|192.168.1.10|5001|6001
```

Valid roles are:

```text
SUBSCRIBER
PUBLISHER
BOTH
```

If registration succeeds, the server responds:

```text
REGISTERED|RQ#|Location_List|Category_List
```

Example:

```text
REGISTERED|01|HALL,EV,JMSB,LOYOLA,SGW,ALL|FIRE,SECURITY,WEATHER,POWER,BUILDING,GENERAL,ALL
```

As mentioned in project project PDF  `REGISTER` in one response example. The correct response name is `REGISTERED`, as specified by Issue #3.

If registration fails, the server responds:

```text
REGISTER-DENIED|RQ#|Reason
```

Example:

```text
REGISTER-DENIED|01|Name already exists
```

A registered client leaves the system using:

```text
DE-REGISTER|RQ#|Name
```

Example:

```text
DE-REGISTER|14|Alice
```

The server removes the client if it exists. If it does not exist, the server ignores the request.

## Subscriptions

A registered `SUBSCRIBER` or `BOTH` client creates a subscription using:

```text
SUBSCRIBE|RQ#|Location|Category
```

Example:

```text
SUBSCRIBE|21|HALL|FIRE
```

If accepted, the server responds:

```text
SUBSCRIBED|RQ#
```

Example:

```text
SUBSCRIBED|21
```

If rejected, the server responds:

```text
SUBSCRIPTION-DENIED|RQ#|Reason
```

Example:

```text
SUBSCRIPTION-DENIED|21|Invalid category
```

A client removes a subscription using:

```text
UNSUBSCRIBE|RQ#|Location|Category
```

Example:

```text
UNSUBSCRIBE|35|HALL|FIRE
```

The initial locations are:

```text
HALL, EV, JMSB, LOYOLA, SGW, ALL
```

The categories are:

```text
FIRE, SECURITY, WEATHER, POWER, BUILDING, GENERAL, ALL
```

`ALL` is a wildcard. An alert for `HALL/FIRE` matches these subscriptions:

```text
HALL/FIRE
ALL/FIRE
HALL/ALL
ALL/ALL
```