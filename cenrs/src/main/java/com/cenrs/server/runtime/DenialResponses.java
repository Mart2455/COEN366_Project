package com.cenrs.server.runtime;

import com.cenrs.protocol.ControlMessage;
import com.cenrs.protocol.MessageType;

import java.util.Optional;

final class DenialResponses {

    private DenialResponses() {
    }

    /**
     * @param incidentId used only by ATTACH_REQ, whose denial also carries the incident ID
     * @return empty when the request type has no denial message (e.g. ALERT_ACK)
     */
    static Optional<ControlMessage> forRequest(
            MessageType type,
            String requestNumber,
            String incidentId,
            String reason
    ) {
        return switch (type) {
            case REGISTER -> Optional.of(
                    ControlMessage.of(MessageType.REGISTER_DENIED, requestNumber, reason)
            );
            case SUBSCRIBE, UNSUBSCRIBE -> Optional.of(
                    ControlMessage.of(MessageType.SUBSCRIPTION_DENIED, requestNumber, reason)
            );
            case PUBLISH_ALERT, UPDATE_ALERT, CLOSE_ALERT -> Optional.of(
                    ControlMessage.of(MessageType.ALERT_DENIED, requestNumber, reason)
            );
            case STATUS_REPORT -> Optional.of(
                    ControlMessage.of(MessageType.STATUS_REJECTED, requestNumber, reason)
            );
            case ATTACH_REQ -> incidentId == null || incidentId.isBlank()
                    ? Optional.empty()
                    : Optional.of(
                            ControlMessage.of(
                                    MessageType.ATTACH_DENIED,
                                    requestNumber,
                                    incidentId,
                                    reason
                            )
                    );
            default -> Optional.empty();
        };
    }
}
