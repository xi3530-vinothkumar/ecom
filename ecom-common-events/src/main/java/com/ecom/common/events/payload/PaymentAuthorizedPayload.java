package com.ecom.common.events.payload;

import com.ecom.common.events.Money;

public record PaymentAuthorizedPayload(String orderId, String transactionId, Money amount) implements EventPayload {}
