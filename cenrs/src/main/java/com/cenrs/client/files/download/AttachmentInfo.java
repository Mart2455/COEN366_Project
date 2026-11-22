package com.cenrs.client.files.download;

import com.cenrs.client.files.AttachmentFiles;

public record AttachmentInfo(
    String requestNumber,
    String incidentId,
    String publisherName,
    String ipAddress,
    int tcpPort,
    String fileName
) {
    public AttachmentInfo {
        requestNumber = requirePositiveNumber(requestNumber);
        incidentId = requireText(incidentId, "incident ID");
        publisherName = requireText(
            publisherName,
            "publisher name"
        );
        ipAddress = requireText(ipAddress, "IP address");

        if (tcpPort < 1 || tcpPort > 65_535) {
            throw new IllegalArgumentException("invalid TCP port");
        }

        fileName = AttachmentFiles.requireSafeFileName(fileName);
    }

    private static String requirePositiveNumber(String value) {
        try {
            if (Long.parseLong(value) < 1) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "invalid request number"
            );
        }

        return value;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                label + " cannot be blank"
            );
        }

        return value;
    }
}