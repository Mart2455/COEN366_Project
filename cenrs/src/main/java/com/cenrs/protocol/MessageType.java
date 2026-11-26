package com.cenrs.protocol;

public enum MessageType {
    //number is number of required fields after message name 
    REGISTER("REGISTER", 6),
    REGISTERED("REGISTERED", 3),
    REGISTER_DENIED("REGISTER-DENIED", 2),
    DE_REGISTER("DE-REGISTER", 2),
    SUBSCRIBE("SUBSCRIBE", 3),
    SUBSCRIBED("SUBSCRIBED", 1),
    SUBSCRIPTION_DENIED("SUBSCRIPTION-DENIED", 2),
    UNSUBSCRIBE("UNSUBSCRIBE", 3), 
    PUBLISH_ALERT("PUBLISH_ALERT" , 7), // 7 fields
    ALERT("ALERT", 9),
    ALERT_ACCEPTED("ALERT_ACCEPTED" , 2),
    ALERT_DENIED("ALERT_DENIED" , 2),
    UPDATE_ALERT("UPDATE_ALERT", 5),
    ALERT_UPDATED("ALERT_UPDATED" , 3),
    CLOSE_ALERT("CLOSE_ALERT", 3),
    ALERT_CLOSED("ALERT_CLOSED", 2),
    ALL_CLEAR("ALL_CLEAR" , 4),
    ALERT_ACK("ALERT_ACK", 4),
    STATUS_REPORT("STATUS_REPORT", 4),
    STATUS_RECORDED("STATUS_RECORDED", 1),
    STATUS_REJECTED("STATUS_REJECTED", 2),
    GET_ATTACHMENT("GET_ATTACHMENT", 4),
    ATTACH_DATA("ATTACH_DATA", 5),
    ATTACH_OK("ATTACH_OK", 3),
    ATTACH_ERROR("ATTACH_ERROR", 4),
    ATTACH_REQ("ATTACH_REQ" , 3),
    ATTACH_INFO("ATTACH_INFO" , 6),
    ATTACH_DENIED("ATTACH_DENIED" , 3),
    // Active incident synchronization over TCP (issue 17)
    GET_ACTIVE_ALERTS("GET_ACTIVE_ALERTS", 2),
    ACTIVE_ALERTS("ACTIVE_ALERTS", 2),
    ACTIVE_ALERT("ACTIVE_ALERT", 9),
    ACTIVE_ALERTS_DENIED("ACTIVE_ALERTS_DENIED", 2);

    
    private final String wireName;
    private final int fieldCount;

    MessageType(String wireName, int fieldCount) {
        this.wireName = wireName;
        this.fieldCount = fieldCount;
    }

    public String wireName() {
        return wireName;
    }

    public int fieldCount() {
        return fieldCount;
    }

    public static MessageType fromWireName(String wireName)
        throws ProtocolException {

        for (MessageType type : values()) {
            if (type.wireName.equals(wireName)) {
                return type;
            }
        }

        throw new ProtocolException(
            "Unknown message type: " + wireName
        );
    }
}
