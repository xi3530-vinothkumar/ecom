package com.ecom.order.web;

import com.ecom.order.domain.OrderStatus;
import java.util.UUID;

public record CheckoutResponse(UUID orderId, OrderStatus status) {}
