package com.cenrs;

import com.cenrs.client.control.ClientController;
import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientRole;
import com.cenrs.protocol.ControlMessage;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Scanner;

public final class Client {
    private Client() {
    }

    public static void main(String[] args) {
        if (args.length != 5) {
            printUsage();
            return;
        }

        try {
            InetSocketAddress server =
                    new InetSocketAddress(
                            InetAddress.getByName(args[0]),
                            Integer.parseInt(args[1])
                    );

            int localUdpPort =
                    Integer.parseInt(args[2]);

            int localTcpPort =
                    Integer.parseInt(args[3]);

            String advertisedIpAddress = args[4];

            try (
                    ClientController controller =
                            new ClientController(
                                    server,
                                    localUdpPort,
                                    localTcpPort,
                                    advertisedIpAddress
                            );

                    Scanner scanner = new Scanner(System.in)
            ) {
                System.out.println(
                        "CENRS client started on UDP port "
                                + controller.localUdpPort()
                );

                printCommands();

                while (true) {
                    System.out.print("cenrs> ");

                    if (!scanner.hasNextLine()) {
                        break;
                    }

                    String line = scanner
                            .nextLine()
                            .trim();

                    if (line.isEmpty()) {
                        continue;
                    }

                    String[] command =
                            line.split("\\s+");

                    try {
                        if (!execute(
                                command,
                                controller
                        )) {
                            break;
                        }
                    } catch (Exception exception) {
                        System.err.println(
                                "Command failed: "
                                        + exception.getMessage()
                        );
                    }
                }
            }
        } catch (Exception exception) {
            System.err.println(
                    "Could not start CENRS client: "
                            + exception.getMessage()
            );
        }
    }

    private static boolean execute(
            String[] command,
            ClientController controller
    ) throws Exception {

        switch (command[0].toLowerCase()) {
            case "register" -> {
                requireLength(command, 3);

                ControlMessage response =
                        controller.register(
                                command[1],
                                ClientRole.valueOf(
                                        command[2].toUpperCase()
                                )
                        );

                printResponse(response);
            }

            case "subscribe" -> {
                requireLength(command, 3);

                ControlMessage response =
                        controller.subscribe(
                                CampusLocation.valueOf(
                                        command[1].toUpperCase()
                                ),
                                AlertCategory.valueOf(
                                        command[2].toUpperCase()
                                )
                        );

                printResponse(response);
            }

            case "unsubscribe" -> {
                requireLength(command, 3);

                controller.unsubscribe(
                        CampusLocation.valueOf(
                                command[1].toUpperCase()
                        ),
                        AlertCategory.valueOf(
                                command[2].toUpperCase()
                        )
                );

                System.out.println(
                        "Unsubscribe request sent"
                );
            }

            case "deregister" -> {
                requireLength(command, 1);
                controller.deregister();

                System.out.println(
                        "De-register request sent"
                );
            }

            case "status" -> {
                System.out.println(
                        "Profile: "
                                + controller
                                        .profile()
                                        .map(Object::toString)
                                        .orElse("not registered")
                );

                System.out.println(
                        "Subscriptions: "
                                + controller.subscriptions()
                );

                System.out.println(
                        "Available locations: "
                                + controller.availableLocations()
                );

                System.out.println(
                        "Available categories: "
                                + controller.availableCategories()
                );
            }

            case "help" -> printCommands();

            case "quit", "exit" -> {
                return false;
            }

            default -> System.err.println(
                    "Unknown command. Enter help."
            );
        }

        return true;
    }

    private static void printResponse(
            ControlMessage response
    ) {
        System.out.println(
                "Server response: "
                        + response.type().wireName()
                        + " "
                        + response.fields()
        );
    }

    private static void requireLength(
            String[] command,
            int expected
    ) {
        if (command.length != expected) {
            throw new IllegalArgumentException(
                    "Incorrect command. Received: "
                            + Arrays.toString(command)
            );
        }
    }

    private static void printUsage() {
        System.out.println(
                "Usage: Client "
                        + "<server-host> "
                        + "<server-udp-port> "
                        + "<local-udp-port> "
                        + "<local-tcp-port> "
                        + "<advertised-ip>"
        );

        System.out.println(
                "Example: Client "
                        + "127.0.0.1 5000 "
                        + "5001 6001 127.0.0.1"
        );
    }

    private static void printCommands() {
        System.out.println("Commands:");
        System.out.println(
                "  register <name> <SUBSCRIBER|PUBLISHER|BOTH>"
        );
        System.out.println(
                "  subscribe <location> <category>"
        );
        System.out.println(
                "  unsubscribe <location> <category>"
        );
        System.out.println("  deregister");
        System.out.println("  status");
        System.out.println("  help");
        System.out.println("  quit");
    }
}