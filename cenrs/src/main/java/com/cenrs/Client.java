package com.cenrs;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

public class Client {
    





    public static void main(String[] args) throws IOException
    {

        try 
        {
            DatagramSocket socket = new DatagramSocket();

            // Create a testing message

            String message = "Hello Server!!!";

            // Convert message into bytes
            byte[] byteArray = message.getBytes(StandardCharsets.UTF_8);

            // Own computer that runs this file
            InetAddress serverAddress = InetAddress.getByName("127.0.0.1");

            DatagramPacket packet = new DatagramPacket(
                byteArray, byteArray.length, serverAddress, 5000
            );

            socket.send(packet);
            socket.close();


        } catch (SocketException e) 
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }


    }
}
