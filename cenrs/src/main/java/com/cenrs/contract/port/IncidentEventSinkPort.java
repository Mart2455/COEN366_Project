package com.cenrs.contract.port;

import com.cenrs.contract.Incident;

/** Receives accepted incident lifecycle changes for downstream notification routing. */
public interface IncidentEventSinkPort {
    void onCreated(Incident incident);

    void onUpdated(Incident incident);

    /** Receives the closed state and the validated reason for ALL_CLEAR routing. */
    void onClosed(Incident incident, String reason);
}
