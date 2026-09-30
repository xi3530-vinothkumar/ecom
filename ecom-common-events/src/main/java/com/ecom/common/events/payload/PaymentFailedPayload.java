package com.ecom.common.events.payload;

public record PaymentFailedPayload(String orderId, String transactionId, String reason) {}
