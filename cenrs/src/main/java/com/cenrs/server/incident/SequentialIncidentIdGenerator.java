package com.cenrs.server.incident;

import com.cenrs.contract.port.IncidentIdGeneratorPort;

import java.util.concurrent.atomic.AtomicLong;

public final class SequentialIncidentIdGenerator
        implements IncidentIdGeneratorPort {

    public static final String PREFIX = "INC";
    public static final long DEFAULT_FIRST_NUMBER = 100;

    private final AtomicLong nextNumber;

    public SequentialIncidentIdGenerator() {
        this(DEFAULT_FIRST_NUMBER);
    }

    public SequentialIncidentIdGenerator(long firstNumber) {
        if (firstNumber < 1) {
            throw new IllegalArgumentException(
                    "first number must be positive"
            );
        }

        nextNumber = new AtomicLong(firstNumber);
    }

    @Override
    public String nextId() {
        // Atomic: two threads can never get the same number.
        return PREFIX + nextNumber.getAndIncrement();
    }

    /** The number the next ID will use. Saved in the snapshot. */
    public long peekNextNumber() {
        return nextNumber.get();
    }

    /** Moves the counter forward after a restore. It never moves backwards. */
    public void advanceTo(long number) {
        nextNumber.accumulateAndGet(number, Math::max);
    }

    /** Returns the number inside an ID like "INC102", or -1 if it has another format. */
    public static long numberOf(String incidentId) {
        if (incidentId == null || !incidentId.startsWith(PREFIX)) {
            return -1;
        }

        try {
            return Long.parseLong(incidentId.substring(PREFIX.length()));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }
}
