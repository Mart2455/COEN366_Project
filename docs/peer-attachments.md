# Peer attachment transfer

Attachments move directly between clients. The Emergency Server returns the
publisher's IP address and TCP port, but it never reads or forwards file bytes.

The subscriber opens a new TCP connection and sends `GET_ATTACHMENT`. The
publisher answers with `ATTACH_DATA`, followed by exactly the declared
`File_Size` bytes. A successful subscriber verifies the SHA-256 checksum,
moves its temporary file into place, and sends `ATTACH_OK`.

Unsafe names, missing files, truncated transfers, and checksum failures are
rejected. Incomplete downloads remain temporary and are deleted. A failed
download may use up to three new TCP connections.

The publisher uses a worker pool so one slow or disconnected subscriber does
not stop other transfers or the client's UDP work. Issue #16 supplies the
`ATTACH_INFO` endpoint, while Issue #25 starts the publisher server for
`PUBLISHER` and `BOTH` clients.