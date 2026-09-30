package com.ecom.inventory.infrastructure;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.payload.OrderCancelledPayload;
import com.ecom.common.events.payload.OrderConfirmedPayload;
import com.ecom.common.events.payload.OrderCreatedPayload;
import com.ecom.inventory.application.ReservationService;
import com.ecom.inventory.domain.SkuQuantity;
import com.ecom.platform.kafka.IdempotentConsumer;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Order saga events. Malformed payloads throw IllegalArgumentException, which the error handler dead-letters. */
@Component
public class OrderEventsListener {

    public static final String GROUP = "inventory-service-order-events-cg";

    private final IdempotentConsumer consumer;
    private final ReservationService reservations;

    public OrderEventsListener(IdempotentConsumer consumer, ReservationService reservations) {
        this.consumer = consumer;
        this.reservations = reservations;
    }

    @KafkaListener(topics = EventTopics.ORDER_CREATED, groupId = GROUP)
    public void onOrderCreated(ConsumerRecord<String, String> rec) {
        consumer.handle(rec, GROUP, env -> {
            OrderCreatedPayload p = env.payloadAs(consumer.mapper(), OrderCreatedPayload.class);
            List<SkuQuantity> lines = p.lines() == null
                    ? List.of()
                    : p.lines().stream().map(l -> new SkuQuantity(l.sku(), l.quantity())).toList();
            reservations.reserve(orderId(p.orderId()), lines);
        });
    }

    @KafkaListener(topics = EventTopics.ORDER_CONFIRMED, groupId = GROUP)
    public void onOrderConfirmed(ConsumerRecord<String, String> rec) {
        consumer.handle(rec, GROUP, env -> reservations.commit(
                orderId(env.payloadAs(consumer.mapper(), OrderConfirmedPayload.class).orderId())));
    }

    @KafkaListener(topics = EventTopics.ORDER_CANCELLED, groupId = GROUP)
    public void onOrderCancelled(ConsumerRecord<String, String> rec) {
        consumer.handle(rec, GROUP, env -> reservations.release(
                orderId(env.payloadAs(consumer.mapper(), OrderCancelledPayload.class).orderId())));
    }

    private static UUID orderId(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("orderId missing");
        }
        return UUID.fromString(raw);
    }
}
