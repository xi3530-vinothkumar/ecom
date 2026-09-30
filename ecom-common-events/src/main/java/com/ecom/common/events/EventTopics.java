package com.ecom.common.events;

import java.util.List;

/**
 * One topic per event type: {@code ecom.<domain>.<event>.v<major>}. The event type string equals the
 * topic name. Must stay in sync with {@code kafka-topics.yaml} (enforced by KafkaTopicsYamlTest), which
 * is also what Terraform provisions on MSK. Each topic has a {@code <topic>.dlt} twin.
 */
public final class EventTopics {

    public static final String ORDER_CREATED = "ecom.order.order-created.v1";
    public static final String ORDER_CONFIRMED = "ecom.order.order-confirmed.v1";
    public static final String ORDER_CANCELLED = "ecom.order.order-cancelled.v1";

    public static final String INVENTORY_RESERVED = "ecom.inventory.inventory-reserved.v1";
    public static final String INVENTORY_RESERVATION_FAILED = "ecom.inventory.inventory-reservation-failed.v1";
    public static final String RESERVATION_EXPIRED = "ecom.inventory.reservation-expired.v1";
    public static final String STOCK_LEVEL_CHANGED = "ecom.inventory.stock-level-changed.v1";

    public static final String PAYMENT_AUTHORIZED = "ecom.payment.payment-authorized.v1";
    public static final String PAYMENT_FAILED = "ecom.payment.payment-failed.v1";
    public static final String PAYMENT_VOIDED = "ecom.payment.payment-voided.v1";

    public static final String PRODUCT_CREATED = "ecom.catalog.product-created.v1";
    public static final String PRODUCT_UPDATED = "ecom.catalog.product-updated.v1";
    public static final String PRODUCT_DELETED = "ecom.catalog.product-deleted.v1";

    public static final String USER_REGISTERED = "ecom.user.user-registered.v1";

    public static final String DLT_SUFFIX = ".dlt";

    public static final List<String> ALL = List.of(
            ORDER_CREATED, ORDER_CONFIRMED, ORDER_CANCELLED,
            INVENTORY_RESERVED, INVENTORY_RESERVATION_FAILED, RESERVATION_EXPIRED, STOCK_LEVEL_CHANGED,
            PAYMENT_AUTHORIZED, PAYMENT_FAILED, PAYMENT_VOIDED,
            PRODUCT_CREATED, PRODUCT_UPDATED, PRODUCT_DELETED,
            USER_REGISTERED);

    public static String dlt(String topic) {
        return topic + DLT_SUFFIX;
    }

    private EventTopics() {}
}
