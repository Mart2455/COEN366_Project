package com.cenrs.harness;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class UdpTestSender {
    private final DatagramSocket socket;
    private final DropFirstFrame dropRule;

    public UdpTestSender(DatagramSocket socket, DropFirstFrame dropRule) {
        this.socket = socket;
        this.dropRule = dropRule;
    }

    public void send(String frame, InetSocketAddress destination) throws IOException {
        //skip this send if the drop rule matches
        if (dropRule.shouldDrop(frame)) {
            return;
        }

        sendPacket(frame, destination);
    }

    public void sendDuplicate(String frame, InetSocketAddress destination) throws IOException {
        //send the same message as two separate packets
        sendPacket(frame, destination);
        sendPacket(frame, destination);
    }

    public ScheduledFuture<Void> sendDelayed(
            String frame,
            InetSocketAddress destination,
            long delayMillis,
            ScheduledExecutorService scheduler) {

        //schedule one packet and let the test keep running
        return scheduler.schedule(() -> {
            sendPacket(frame, destination);
            return null;
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void sendPacket(String frame, InetSocketAddress destination) throws IOException {
        //convert the message to bytes
        byte[] data = frame.getBytes(StandardCharsets.UTF_8);

        //send one UDP packet to the receiver
        DatagramPacket packet = new DatagramPacket(data, data.length, destination);
        socket.send(packet);
    }
}