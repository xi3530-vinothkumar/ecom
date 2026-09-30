package com.ecom.common.events.payload;

import java.util.List;

/** reason: OUT_OF_STOCK | PAYMENT_DECLINED | PAYMENT_TIMEOUT. */
public record OrderCancelledPayload(String orderId, String userId, String reason, List<OrderLinePayload> lines) {

    public static final String OUT_OF_STOCK = "OUT_OF_STOCK";
    public static final String PAYMENT_DECLINED = "PAYMENT_DECLINED";
    public static final String PAYMENT_TIMEOUT = "PAYMENT_TIMEOUT";
}
