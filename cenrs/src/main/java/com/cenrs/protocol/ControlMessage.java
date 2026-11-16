package com.cenrs.protocol;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record ControlMessage(
    MessageType type,
    List<String> fields
) {
    public ControlMessage {
        type = Objects.requireNonNull(
            type,
            "message type cannot be null"
        );

        fields = List.copyOf(
            Objects.requireNonNull(
                fields,
                "message fields cannot be null"
            )
        );

        if (fields.size() != type.fieldCount()) {
            throw new IllegalArgumentException(
                type.wireName()
                    + " requires "
                    + type.fieldCount()
                    + " fields"
            );
        }
    }

    public static ControlMessage of(
        MessageType type,
        String... fields
    ) {
        return new ControlMessage(
            type,
            Arrays.asList(fields)
        );
    }

    public String field(int index) {
        return fields.get(index);
    }
}