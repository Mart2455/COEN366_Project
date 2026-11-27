package com.cenrs.harness;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class TcpAttachmentFixture {
    private TcpAttachmentFixture() {
    }

    public static void writeTruncated(
            OutputStream output,
            String requestId,
            String incidentId,
            String fileName,
            byte[] file) throws IOException, NoSuchAlgorithmException {

        writeHeader(output, requestId, incidentId, fileName, file);

        //leave out the last byte
        output.write(file, 0, file.length - 1);
        output.flush();
    }

    public static void writeCorrupted(
            OutputStream output,
            String requestId,
            String incidentId,
            String fileName,
            byte[] file) throws IOException, NoSuchAlgorithmException {

        writeHeader(output, requestId, incidentId, fileName, file);

        //send every byte before the last one normally
        output.write(file, 0, file.length - 1);

        //flip one bit in the last byte
        output.write(file[file.length - 1] ^ 1);
        output.flush();
    }

    public static void serveTruncated(Socket connection, byte[] file)
            throws IOException, NoSuchAlgorithmException {

        //close this connection when its transfer ends
        try (connection) {
            connection.setSoTimeout(5000);

            //wait for one complete request line
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
            );
            String request = reader.readLine();
            if (request == null) {
                throw new IOException("Subscriber disconnected before sending a request");
            }

            //read the fields from the test request
            String[] fields = request.split("\\|", -1);
            if (fields.length != 5 || !fields[0].equals("GET_ATTACHMENT")) {
                throw new IOException("Expected a GET_ATTACHMENT request");
            }

            //reply using the request number incident ID and file name
            writeTruncated(connection.getOutputStream(), fields[1], fields[3], fields[4], file);
        }
    }

    private static void writeHeader(
            OutputStream output,
            String requestId,
            String incidentId,
            String fileName,
            byte[] file) throws IOException, NoSuchAlgorithmException {

        if (file.length == 0) {
            throw new IllegalArgumentException("Use a nonempty file for this test");
        }

        //use the size and checksum of the original file
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(file);
        String checksum = HexFormat.of().formatHex(hash);

        String header = String.join("|", "ATTACH_DATA", requestId, incidentId,
                                    fileName, Integer.toString(file.length), checksum);
        output.write((header + "\n").getBytes(StandardCharsets.UTF_8));
    }
}