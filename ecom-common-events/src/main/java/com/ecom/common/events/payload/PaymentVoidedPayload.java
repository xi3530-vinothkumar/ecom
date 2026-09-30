package com.ecom.common.events.payload;

public record PaymentVoidedPayload(String orderId, String transactionId) {}
