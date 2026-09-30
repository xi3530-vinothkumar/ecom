package com.ecom.order.application;

public record CheckoutCommand(String userId, String idempotencyKey, String paymentMethodRef, String shippingAddressId) {}
