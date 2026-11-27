# Network failure test harness

From the `cenrs` folder, run the 11 harness tests with Maven.

```powershell
mvn "-Dtest=DropFirstFrameTest,UdpTestSenderTest,TcpAttachmentFixtureTest,TcpAttachmentPeerTest" test
```

The helpers and tests are in `src/test/java/com/cenrs/harness`.

- **UDP loss.** Use `DropFirstFrame` with `UdpTestSender.send` to drop the first exact match. Other frames and later copies pass through.
- **UDP duplication and delay.** Use `sendDuplicate` to send two copies or `sendDelayed` to schedule one packet after a delay in milliseconds.
- **TCP attachment faults.** Use `writeTruncated` to omit the last byte or `writeCorrupted` to change it. Both keep the original size and checksum in the header.
- **TCP peer scenarios.** `TcpAttachmentPeerTest` checks early end of file, connection refusal, and receipt of corrupted data with the original checksum. It also checks that Bob receives his response while Alice’s request is still incomplete.

The socket tests run on loopback and use ports assigned by the operating system. Attachment data stays in memory.