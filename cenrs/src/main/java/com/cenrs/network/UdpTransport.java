package com.cenrs.network;

import com.cenrs.contract.port.TransportPort;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class UdpTransport
        implements TransportPort {

    private final DatagramSocket socket;

    public UdpTransport(DatagramSocket socket) {
        this.socket = Objects.requireNonNull(socket);
    }

    @Override
    public synchronized void send(
            InetSocketAddress destination,
            byte[] message
    ) throws IOException {

        Objects.requireNonNull(destination);
        Objects.requireNonNull(message);

        DatagramPacket packet = new DatagramPacket(
                message,
                message.length,
                destination
        );

        socket.send(packet);

        System.out.println(
                "[UDP SENT] "
                        + destination
                        + " "
                        + new String(
                                message,
                                StandardCharsets.UTF_8
                        )
        );
    }
}