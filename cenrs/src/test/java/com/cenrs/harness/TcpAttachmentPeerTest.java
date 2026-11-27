package com.cenrs.harness;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.fail;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class TcpAttachmentPeerTest {

    @Test
    public void subscriberSeesEarlyEndOfFile() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        byte[] file = "abc".getBytes(StandardCharsets.UTF_8);
        String request = "GET_ATTACHMENT|94|Alice|INC102|map.txt";

        //let the system choose a local port for the publisher
        try (ServerSocket listener = new ServerSocket(0, 1, local);
             Socket subscriber = new Socket(local, listener.getLocalPort())) {

            subscriber.setSoTimeout(2000);
            listener.setSoTimeout(2000);

            //the subscriber requests the file
            subscriber.getOutputStream().write((request + "\n").getBytes(StandardCharsets.UTF_8));

            try (Socket publisher = listener.accept()) {
                publisher.setSoTimeout(2000);
                BufferedReader requests = new BufferedReader(
                    new InputStreamReader(publisher.getInputStream(), StandardCharsets.UTF_8)
                );
                assertEquals(request, requests.readLine());

                //send the short file then close the publisher socket
                TcpAttachmentFixture.writeTruncated(
                    publisher.getOutputStream(), "94", "INC102", "map.txt", file
                );
            }

            //read until the publisher closes its end
            byte[] received = subscriber.getInputStream().readAllBytes();

            String checksum = "ba7816bf8f01cfea414140de5dae2223"
                            + "b00361a396177a9cb410ff61f20015ad";
            String expected = "ATTACH_DATA|94|INC102|map.txt|3|" + checksum + "\nab";
            assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), received);
        }
    }

    @Test
    public void refusesConnectionWhenPublisherIsUnavailable() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();

        try (Socket reservedPort = new Socket();
             Socket subscriber = new Socket()) {

            //reserve a local port without listening on it
            reservedPort.bind(new InetSocketAddress(local, 0));
            InetSocketAddress destination =
                new InetSocketAddress(local, reservedPort.getLocalPort());

            try {
                //give the subscriber up to two seconds to connect
                subscriber.connect(destination, 2000);
                fail("The connection should have been refused");
            } catch (ConnectException expected) {
                //the publisher port refused the connection
            }
        }
    }

    @Test
    public void bobReceivesFileWhileAliceIsStillSendingHerRequest() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        byte[] file = "abc".getBytes(StandardCharsets.UTF_8);
        String checksum = "ba7816bf8f01cfea414140de5dae2223"
                        + "b00361a396177a9cb410ff61f20015ad";

        //give each connection its own worker
        try (ExecutorService workers = Executors.newFixedThreadPool(2);
             ServerSocket listener = new ServerSocket(0, 2, local);
             Socket alice = new Socket(local, listener.getLocalPort());
             Socket alicePeer = listener.accept();
             Socket bob = new Socket(local, listener.getLocalPort());
             Socket bobPeer = listener.accept()) {

            alice.setSoTimeout(3000);
            bob.setSoTimeout(3000);

            //leave Alice waiting for the final newline
            String aliceRequest = "GET_ATTACHMENT|94|Alice|INC102|map.txt";
            alice.getOutputStream().write(aliceRequest.getBytes(StandardCharsets.UTF_8));
            Future<Void> aliceTask = workers.submit(() -> {
                TcpAttachmentFixture.serveTruncated(alicePeer, file);
                return null;
            });

            //Bob sends a complete request on the other connection
            Future<Void> bobTask = workers.submit(() -> {
                TcpAttachmentFixture.serveTruncated(bobPeer, file);
                return null;
            });
            String bobRequest = "GET_ATTACHMENT|95|Bob|INC102|map.txt\n";
            bob.getOutputStream().write(bobRequest.getBytes(StandardCharsets.UTF_8));

            //Bob must receive his response before Alice finishes
            String bobResponse = new String(bob.getInputStream().readAllBytes(),
                                            StandardCharsets.UTF_8);
            assertEquals("ATTACH_DATA|95|INC102|map.txt|3|" + checksum + "\nab", bobResponse);
            bobTask.get(1, TimeUnit.SECONDS);

            //finish the pending request and check the response for Alice
            alice.getOutputStream().write('\n');
            String aliceResponse = new String(alice.getInputStream().readAllBytes(),
                                              StandardCharsets.UTF_8);
            assertEquals("ATTACH_DATA|94|INC102|map.txt|3|" + checksum + "\nab", aliceResponse);
            aliceTask.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    public void subscriberReceivesCorruptedFileWithOriginalChecksum() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        byte[] file = "abc".getBytes(StandardCharsets.UTF_8);

        try (ServerSocket listener = new ServerSocket(0, 1, local);
             Socket subscriber = new Socket(local, listener.getLocalPort())) {

            subscriber.setSoTimeout(2000);

            //send the corrupted file over the local connection
            try (Socket publisher = listener.accept()) {
                TcpAttachmentFixture.writeCorrupted(
                    publisher.getOutputStream(), "94", "INC102", "map.txt", file
                );
            }

            //expect the checksum of abc and the changed body abb
            String checksum = "ba7816bf8f01cfea414140de5dae2223"
                            + "b00361a396177a9cb410ff61f20015ad";
            String expected = "ATTACH_DATA|94|INC102|map.txt|3|" + checksum + "\nabb";

            byte[] received = subscriber.getInputStream().readAllBytes();
            assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), received);
        }
    }

}