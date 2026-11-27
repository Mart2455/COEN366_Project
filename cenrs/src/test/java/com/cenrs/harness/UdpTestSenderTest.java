package com.cenrs.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class UdpTestSenderTest {

    @Test
    public void dropsFirstAckAndDeliversSecond() throws Exception {
        String ack = "ALERT_ACK|83|INC102|1|Alice";

        //keep all traffic on this computer
        InetAddress local = InetAddress.getLoopbackAddress();

        //port 0 lets the system choose a free port for each socket
        try (DatagramSocket senderSocket = new DatagramSocket(0, local);
             DatagramSocket receiver = new DatagramSocket(0, local)) {

            //send to the port assigned to the receiver
            InetSocketAddress destination =
                new InetSocketAddress(local, receiver.getLocalPort());

            //set the sender to drop the first matching ACK
            UdpTestSender sender = new UdpTestSender(senderSocket, new DropFirstFrame(ack));

            //reserve space for the incoming message
            byte[] buffer = new byte[512];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            //the first ACK should never reach the receiver
            sender.send(ack, destination);
            receiver.setSoTimeout(250);
            try {
                receiver.receive(packet);
                fail("The first ACK reached the receiver");
            } catch (SocketTimeoutException expected) {
                //nothing arrived within 250 ms as expected
            }

            //send the same ACK again and wait up to 2 seconds
            sender.send(ack, destination);
            receiver.setSoTimeout(2000);
            receiver.receive(packet);

            //convert only the received bytes back to text
            String received = new String(packet.getData(), packet.getOffset(),
                                         packet.getLength(), StandardCharsets.UTF_8);
            assertEquals(ack, received);
        }
    }

    @Test
    public void sendsExactlyTwoCopies() throws Exception {
        String ack = "ALERT_ACK|83|INC102|1|Alice";
        InetAddress local = InetAddress.getLoopbackAddress();

        //open two sockets on this computer
        try (DatagramSocket senderSocket = new DatagramSocket(0, local);
             DatagramSocket receiver = new DatagramSocket(0, local)) {

            InetSocketAddress destination =
                new InetSocketAddress(local, receiver.getLocalPort());
            UdpTestSender sender = new UdpTestSender(senderSocket, new DropFirstFrame(ack));
            receiver.setSoTimeout(2000);

            //one call should send two copies even if the drop rule matches
            sender.sendDuplicate(ack, destination);

            //receive each copy and check its contents
            for (int copy = 0; copy < 2; copy++) {
                byte[] buffer = new byte[512];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                receiver.receive(packet);

                String received = new String(packet.getData(), packet.getOffset(),
                                             packet.getLength(), StandardCharsets.UTF_8);
                assertEquals(ack, received);
            }

            //check that a third packet never arrives
            receiver.setSoTimeout(250);
            byte[] buffer = new byte[512];
            try {
                receiver.receive(new DatagramPacket(buffer, buffer.length));
                fail("A third packet arrived");
            } catch (SocketTimeoutException expected) {
                //only two copies arrived
            }
        }
    }

    @Test
    public void deliversAckAfterConfiguredDelay() throws Exception {
        String ack = "ALERT_ACK|83|INC102|1|Alice";
        long delayMillis = 300;
        InetAddress local = InetAddress.getLoopbackAddress();

        //close both sockets and the scheduler when the test ends
        try (DatagramSocket senderSocket = new DatagramSocket(0, local);
             DatagramSocket receiver = new DatagramSocket(0, local);
             ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {

            InetSocketAddress destination =
                new InetSocketAddress(local, receiver.getLocalPort());
            UdpTestSender sender = new UdpTestSender(senderSocket, new DropFirstFrame(ack));
            receiver.setSoTimeout(3000);

            byte[] buffer = new byte[512];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            //start the clock and schedule the ACK
            long start = System.nanoTime();
            ScheduledFuture<Void> pending =
                sender.sendDelayed(ack, destination, delayMillis, scheduler);

            //wait for the packet and measure the elapsed time
            receiver.receive(packet);
            long elapsed = System.nanoTime() - start;

            //check that the send completed and the delay was respected
            pending.get(1, TimeUnit.SECONDS);
            assertTrue("ACK arrived too early",
                       elapsed >= TimeUnit.MILLISECONDS.toNanos(delayMillis));

            //check the message that arrived
            String received = new String(packet.getData(), packet.getOffset(),
                                         packet.getLength(), StandardCharsets.UTF_8);
            assertEquals(ack, received);
        }
    }

    @Test
    public void dropsFirstAlertAndLetsOtherTrafficThrough() throws Exception {
        String alert = "ALERT|83|INC102|1|FIRE|HALL|CRITICAL|Fire alarm|Leave now|NONE";
        String otherFrame = "SUBSCRIBED|21";
        InetAddress local = InetAddress.getLoopbackAddress();

        try (DatagramSocket senderSocket = new DatagramSocket(0, local);
             DatagramSocket receiver = new DatagramSocket(0, local)) {

            InetSocketAddress destination =
                new InetSocketAddress(local, receiver.getLocalPort());
            UdpTestSender sender = new UdpTestSender(senderSocket, new DropFirstFrame(alert));
            receiver.setSoTimeout(2000);

            //drop the first alert and send an unrelated reply
            sender.send(alert, destination);
            sender.send(otherFrame, destination);

            //the reply should be the first packet received
            byte[] buffer = new byte[512];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            receiver.receive(packet);
            assertEquals(otherFrame, new String(packet.getData(), packet.getOffset(),
                                                packet.getLength(), StandardCharsets.UTF_8));

            //reset the receive capacity before reading the longer alert
            packet.setLength(buffer.length);

            //send the alert again and check that it arrives
            sender.send(alert, destination);
            receiver.receive(packet);
            assertEquals(alert, new String(packet.getData(), packet.getOffset(),
                                           packet.getLength(), StandardCharsets.UTF_8));
        }
    }

}