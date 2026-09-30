package com.ecom.order.application;

import com.ecom.order.domain.OrderStatus;
import java.util.UUID;

/** {@code created=false} means an idempotent replay returned the order created by an earlier request. */
public record CheckoutResult(UUID orderId, OrderStatus status, boolean created) {}
