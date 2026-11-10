package com.cenrs.contract;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class ContractValidation {
    private ContractValidation() {
    }

    static String requiredText(String value, String fieldName, int maxBytes) {
        Objects.requireNonNull(value, fieldName + " cannot be null");

        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }

        if (value.contains("|") || value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException(
                fieldName + " contains a reserved character"
            );
        }

        int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > maxBytes) {
            throw new IllegalArgumentException(
                fieldName + " exceeds " + maxBytes + " bytes"
            );
        }

        return value;
    }

    static int validPort(int port, String fieldName) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                fieldName + " must be between 1 and 65535"
            );
        }

        return port;
    }

    static long positive(long value, String fieldName) {
        if (value < 1) {
            throw new IllegalArgumentException(
                fieldName + " must be positive"
            );
        }

        return value;
    }
}