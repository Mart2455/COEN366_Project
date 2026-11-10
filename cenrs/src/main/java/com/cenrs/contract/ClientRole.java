package com.cenrs.contract;

public enum ClientRole {
    SUBSCRIBER,
    PUBLISHER,
    BOTH;

    public boolean canSubscribe() {
        return this == SUBSCRIBER || this == BOTH;
    }

    public boolean canPublish() {
        return this == PUBLISHER || this == BOTH;
    }
}