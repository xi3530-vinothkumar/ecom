package com.ecom.order.domain;

/**
 * PENDING is the only non-terminal state; the saga moves it exactly once to CONFIRMED or CANCELLED.
 * Every persisted transition is a guarded update on {@code status = 'PENDING'}, so the first event to
 * win decides and any conflicting later event matches zero rows.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    CANCELLED;

    public boolean canTransitionTo(OrderStatus target) {
        return this == PENDING && (target == CONFIRMED || target == CANCELLED);
    }

    public boolean isTerminal() {
        return this != PENDING;
    }
}
