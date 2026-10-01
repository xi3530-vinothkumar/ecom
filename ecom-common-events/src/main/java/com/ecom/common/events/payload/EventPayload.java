package com.ecom.common.events.payload;

/**
 * Closed set of event payloads carried in an {@link com.ecom.common.events.EventEnvelope}; nested values
 * such as {@link OrderLinePayload} are not events. New event types must be added to {@code permits}.
 */
public sealed interface EventPayload
        permits InventoryReservationFailedPayload, InventoryReservedPayload, OrderCancelledPayload,
                OrderConfirmedPayload, OrderCreatedPayload, PaymentAuthorizedPayload,
                PaymentFailedPayload, PaymentVoidedPayload, ProductChangedPayload, ProductDeletedPayload,
                ReservationExpiredPayload, StockLevelChangedPayload, UserRegisteredPayload {}
