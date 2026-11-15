package com.cenrs.contract;

public enum Severity {
    INFO,
    WARNING,
    CRITICAL;

    public boolean requiresAcknowledgement() {
        return this == CRITICAL;
    }
}