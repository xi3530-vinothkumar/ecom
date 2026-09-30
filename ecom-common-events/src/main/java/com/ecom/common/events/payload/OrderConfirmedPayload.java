package com.ecom.common.events.payload;

import com.ecom.common.events.Money;
import java.util.List;

public record OrderConfirmedPayload(String orderId, String userId, List<OrderLinePayload> lines, Money total) {}
