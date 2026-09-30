package com.ecom.order.web;

import com.ecom.order.domain.CancelReason;
import com.ecom.order.domain.Order;
import com.ecom.order.domain.OrderStatus;
import java.time.Instant;
import java.util.UUID;

public record OrderSummaryView(UUID orderId, OrderStatus status, CancelReason cancelReason, MoneyView total, Instant createdAt) {

    static OrderSummaryView of(Order o) {
        return new OrderSummaryView(o.id(), o.status(), o.cancelReason(), MoneyView.of(o.total()), o.createdAt());
    }
}
