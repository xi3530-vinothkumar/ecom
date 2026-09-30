package com.ecom.order.application;

import com.ecom.common.events.payload.OrderLinePayload;
import com.ecom.order.domain.Order;
import java.util.List;

final class OrderEvents {

    static List<OrderLinePayload> lines(Order order) {
        return order.lines().stream().map(l -> new OrderLinePayload(l.sku(), l.quantity())).toList();
    }

    private OrderEvents() {}
}
