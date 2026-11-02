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
        // TODO: Create the UDP socket and listen to message here

        // Creates the server
        try
        {
            
            EmergencyServer server = new EmergencyServer(5000);
            System.err.println("Server started on UDP port 5000");

            // ! Need to allow the server to receive a message
            byte[] Buffer = new byte[1024];
            DatagramPacket Packet = new DatagramPacket(Buffer, Buffer.length);

            DatagramSocket socket;
            // Allows the server to receive the message
            server.socket.receive(Packet);


        }
        catch (SocketException Error)
        {
            System.err.println("Could not start server: " + Error.getMessage());
        }

        

        



        

    }

}
