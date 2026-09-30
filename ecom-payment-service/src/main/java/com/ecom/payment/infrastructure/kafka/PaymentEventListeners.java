package com.ecom.payment.infrastructure.kafka;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.payload.InventoryReservedPayload;
import com.ecom.common.events.payload.OrderCancelledPayload;
import com.ecom.common.events.payload.OrderConfirmedPayload;
import com.ecom.payment.application.PaymentSagaService;
import com.ecom.platform.kafka.IdempotentConsumer;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventListeners {

    static final String SAGA_GROUP = "payment-service-saga-cg";
    static final String ORDER_EVENTS_GROUP = "payment-service-order-events-cg";

    private final IdempotentConsumer consumer;
    private final PaymentSagaService saga;

    public PaymentEventListeners(IdempotentConsumer consumer, PaymentSagaService saga) {
        this.consumer = consumer;
        this.saga = saga;
    }

    @KafkaListener(topics = EventTopics.INVENTORY_RESERVED, groupId = SAGA_GROUP)
    void onInventoryReserved(ConsumerRecord<String, String> rec) {
        consumer.handle(rec, SAGA_GROUP, env -> saga.onInventoryReserved(
                orderId(env.payloadAs(consumer.mapper(), InventoryReservedPayload.class).orderId())));
    }

    @KafkaListener(topics = {EventTopics.ORDER_CANCELLED, EventTopics.ORDER_CONFIRMED}, groupId = ORDER_EVENTS_GROUP)
    void onOrderEvent(ConsumerRecord<String, String> rec) {
        consumer.handle(rec, ORDER_EVENTS_GROUP, env -> {
            switch (rec.topic()) {
                case EventTopics.ORDER_CANCELLED -> saga.onOrderCancelled(
                        orderId(env.payloadAs(consumer.mapper(), OrderCancelledPayload.class).orderId()));
                case EventTopics.ORDER_CONFIRMED -> saga.onOrderConfirmed(
                        orderId(env.payloadAs(consumer.mapper(), OrderConfirmedPayload.class).orderId()));
                default -> throw new IllegalArgumentException("Unexpected topic " + rec.topic());
            }
        });
    }

    /** A malformed id can never succeed: IllegalArgumentException goes straight to the DLT. */
    private static UUID orderId(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Event payload lacks orderId");
        }
        return UUID.fromString(raw);
    }
}
