package com.cenrs.server.runtime;

import com.cenrs.contract.Incident;
import com.cenrs.contract.port.IncidentEventSinkPort;

public final class LoggingIncidentEventSink
        implements IncidentEventSinkPort {

    @Override
    public void onCreated(Incident incident) {
        System.out.println("[INCIDENT CREATED] " + incident);
    }

    @Override
    public void onUpdated(Incident incident) {
        System.out.println("[INCIDENT UPDATED] " + incident);
    }

    @Override
    public void onClosed(Incident incident, String reason) {
        System.out.println("[INCIDENT CLOSED] " + incident + " reason=" + reason);
    }
}
