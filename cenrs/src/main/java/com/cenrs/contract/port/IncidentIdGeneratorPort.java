package com.cenrs.contract.port;

/** Supplies unique incident IDs that must not be reused after a server restart. */
public interface IncidentIdGeneratorPort {
    String nextId();
}
