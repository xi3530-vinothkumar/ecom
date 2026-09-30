package com.ecom.order.domain;

/** Last saga signal observed for an order (order_saga_state, observability only). */
public enum SagaStep {
    ORDER_CREATED,
    INVENTORY_RESERVED,
    INVENTORY_RESERVATION_FAILED,
    PAYMENT_AUTHORIZED,
    PAYMENT_AUTHORIZED_LATE,
    PAYMENT_FAILED,
    RESERVATION_EXPIRED,
    PAYMENT_VOIDED
}
