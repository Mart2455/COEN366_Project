# COEN366 Project

## Requirements

- Java 25
- Maven 3.9 or newer

## Build and test

From the repository root:

```bash
cd cenrs
mvn clean test
```
## Run the Emergency Server

From `cenrs/`, after `mvn clean package`:

```bash
java -cp target/classes com.cenrs.EmergencyServer [UDP_Port] [TCP_Port] [State_File]
```

Defaults: UDP port `5000`, TCP port `6000` (active-incident synchronization), state file `data/cenrs-state.txt`.

Press Ctrl+C to stop the server. It saves a final snapshot first. When it starts again, it restores the snapshot (see `docs/persistence.md`).
