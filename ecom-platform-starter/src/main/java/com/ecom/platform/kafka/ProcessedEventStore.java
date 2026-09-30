package com.ecom.platform.kafka;

import java.util.UUID;

/**
 * Dedup store for the idempotent-consumer pattern. {@link #markProcessed} must participate in the
 * caller's transaction (JDBC impl) or be atomic on its own (e.g. Redis SETNX for Cart).
 */
public interface ProcessedEventStore {

    /** @return true if this (event, consumer) was not seen before and is now recorded. */
    boolean markProcessed(UUID eventId, String consumer);

    /**
     * Undo {@link #markProcessed} after a failed handler. Only called when there is no transaction to
     * roll the marker back (non-JDBC stores); otherwise a retry would be skipped as a duplicate.
     */
    default void unmark(UUID eventId, String consumer) {}
}
