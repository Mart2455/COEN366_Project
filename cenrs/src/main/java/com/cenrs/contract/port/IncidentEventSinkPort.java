package com.cenrs.contract.port;

import com.cenrs.contract.Incident;

/** Receives accepted incident lifecycle changes for downstream notification routing. */
public interface IncidentEventSinkPort {
    void onCreated(Incident incident);

    void onUpdated(Incident incident);

    void onClosed(Incident incident);
}
