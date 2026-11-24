package com.cenrs;

import com.cenrs.server.runtime.CenrsServer;

import java.nio.file.Path;

/**
 * Starts the Emergency Server.
 *
 * Usage: EmergencyServer [UDP_Port] [TCP_Port] [State_File]
 * Defaults: 5000, 6000, data/cenrs-state.txt
 */
public final class EmergencyServer {
    private EmergencyServer() {
    }

    public static void main(String[] args) {
        int udpPort = args.length >= 1
                ? Integer.parseInt(args[0])
                : CenrsServer.DEFAULT_UDP_PORT;

        int tcpPort = args.length >= 2
                ? Integer.parseInt(args[1])
                : CenrsServer.DEFAULT_TCP_PORT;

        Path stateFile = args.length >= 3
                ? Path.of(args[2])
                : Path.of("data", "cenrs-state.txt");

        try (CenrsServer server = new CenrsServer(udpPort, tcpPort, stateFile)) {
            // Ctrl+C still saves a final snapshot and closes the sockets.
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));

            server.run();
        } catch (Exception exception) {
            System.err.println(
                    "Could not run CENRS server: " + exception.getMessage()
            );
        }
    }
}
