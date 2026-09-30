package com.ecom.payment.application;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.payload.PaymentAuthorizedPayload;
import com.ecom.common.events.payload.PaymentFailedPayload;
import com.ecom.common.events.payload.PaymentVoidedPayload;
import com.ecom.payment.domain.PaymentTransaction;
import com.ecom.platform.kafka.OutboxWriter;
import org.springframework.stereotype.Component;

/** Outbox emission of Payment's domain events, keyed by orderId. Caller must hold a transaction. */
@Component
public class PaymentEvents {

    private final OutboxWriter outbox;

    public PaymentEvents(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void authorized(PaymentTransaction tx) {
        String orderId = tx.orderId().toString();
        outbox.publish(EventTopics.PAYMENT_AUTHORIZED, orderId,
                new PaymentAuthorizedPayload(orderId, tx.id().toString(), tx.amountMoney()));
    }

    public void failed(PaymentTransaction tx) {
        String orderId = tx.orderId().toString();
        outbox.publish(EventTopics.PAYMENT_FAILED, orderId,
                new PaymentFailedPayload(orderId, tx.id().toString(), tx.failureReason()));
    }

    public void voided(PaymentTransaction tx) {
        String orderId = tx.orderId().toString();
        outbox.publish(EventTopics.PAYMENT_VOIDED, orderId, new PaymentVoidedPayload(orderId, tx.id().toString()));
    }
}
