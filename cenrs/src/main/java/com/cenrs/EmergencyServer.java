package com.cenrs;
import java.io.IOException;
import java.net.*;



public class EmergencyServer 
{
    // Create the socket
    public DatagramSocket socket;

    public EmergencyServer(int port) throws SocketException 
    {
        socket = new DatagramSocket(port); 
    }

    public static void main(String[] args) throws IOException
    {
        // TODO: Create the UDP socket

        try
        {
            
            EmergencyServer server = new EmergencyServer(5000);
            System.err.println("Server started on UDP port 5000");

            byte[] Buffer = new byte[1024];
            DatagramPacket Packet = new DatagramPacket(Buffer, Buffer.length);

            // Allows the server to receive the message
            server.socket.receive(Packet);
            
            // Translate the received message 
            // String(byte[] bytes, int offset, int length, Charset charset)

            String message = new String(
                Packet.getData(),
                Packet.getOffset(),
                Packet.getLength(),
                java.nio.charset.StandardCharsets.UTF_8
            );
            // We can finally print the message
            System.out.println("Received: " + message); 

        }
        catch (SocketException Error)
        {
            System.err.println("Could not start server: " + Error.getMessage());
        }
    }

}
