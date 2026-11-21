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
    ALERT_ACCEPTED("ALERT_ACCEPTED" , 2),
    ALERT_DENIED("ALERT_DENIED" , 2),
    UPDATE_ALERT("UPDATE_ALERT", 5),
    ALERT_UPDATED("ALERT_UPDATED" , 3),
    CLOSE_ALERT("CLOSE_ALERT", 3),
    ALERT_CLOSED("ALERT_CLOSED", 2),
    ALL_CLEAR("ALL_CLEAR" , 4),
    ALERT_ACK("ALERT_ACK", 4);

    
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