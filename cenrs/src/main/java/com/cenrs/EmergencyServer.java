package com.cenrs;

import com.cenrs.server.runtime.PhaseOneServer;

import java.nio.file.Path;

public final class EmergencyServer {
    private EmergencyServer() {
    }

    public static void main(String[] args) {
        int udpPort = args.length >= 1
                ? Integer.parseInt(args[0])
                : 5000;

        Path stateFile = args.length >= 2
                ? Path.of(args[1])
                : Path.of("data", "cenrs-state.txt");

        try (
                PhaseOneServer server =
                        new PhaseOneServer(
                                udpPort,
                                stateFile
                        )
        ) {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(server::close)
            );

            server.run();
        } catch (Exception exception) {
            System.err.println(
                    "Could not run CENRS server: "
                            + exception.getMessage()
            );
        }
    }
}