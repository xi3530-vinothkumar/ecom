package com.ecom.order.domain;

import com.ecom.common.events.payload.OrderCancelledPayload;

public enum CancelReason {
    OUT_OF_STOCK(OrderCancelledPayload.OUT_OF_STOCK),
    PAYMENT_DECLINED(OrderCancelledPayload.PAYMENT_DECLINED),
    PAYMENT_TIMEOUT(OrderCancelledPayload.PAYMENT_TIMEOUT);

    private final String eventValue;

    CancelReason(String eventValue) {
        this.eventValue = eventValue;
    }

    public String eventValue() {
        return eventValue;
    }
}
