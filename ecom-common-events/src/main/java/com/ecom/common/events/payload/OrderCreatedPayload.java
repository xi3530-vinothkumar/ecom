package com.ecom.common.events.payload;

import java.util.List;

public record OrderCreatedPayload(String orderId, String userId, List<OrderLinePayload> lines) implements EventPayload {}
