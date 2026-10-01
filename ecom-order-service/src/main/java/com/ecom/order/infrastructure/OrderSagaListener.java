package com.ecom.order.infrastructure;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.payload.InventoryReservationFailedPayload;
import com.ecom.common.events.payload.InventoryReservedPayload;
import com.ecom.common.events.payload.PaymentAuthorizedPayload;
import com.ecom.common.events.payload.PaymentFailedPayload;
import com.ecom.common.events.payload.PaymentVoidedPayload;
import com.ecom.common.events.payload.ReservationExpiredPayload;
import com.ecom.order.application.OrderSagaService;
import com.ecom.platform.kafka.IdempotentConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderSagaListener {

    public static final String GROUP = "order-service-saga-cg";

    private final IdempotentConsumer consumer;
    private final ObjectMapper mapper;
    private final OrderSagaService saga;

    public OrderSagaListener(IdempotentConsumer consumer, OrderSagaService saga) {
        this.consumer = consumer;
        this.mapper = consumer.mapper();
        this.saga = saga;
    }

    @KafkaListener(topics = EventTopics.INVENTORY_RESERVED, groupId = GROUP)
    void onInventoryReserved(ConsumerRecord<String, String> rec) {
        consumer.handle(rec, GROUP, env -> saga.onInventoryReserved(env.payloadAs(mapper, InventoryReservedPayload.class)));
    }

    @KafkaListener(topics = EventTopics.INVENTORY_RESERVATION_FAILED, groupId = GROUP)
    void onInventoryReservationFailed(ConsumerRecord<String, String> rec) {
        consumer.handle(rec, GROUP, env ->
                saga.onInventoryReservationFailed(env.payloadAs(mapper, InventoryReservationFailedPayload.class)));
    }

    @KafkaListener(topics = EventTopics.PAYMENT_AUTHORIZED, groupId = GROUP)
    void onPaymentAuthorized(ConsumerRecord<String, String> rec) {
        consumer.handle(rec, GROUP, env -> saga.onPaymentAuthorized(env.payloadAs(mapper, PaymentAuthorizedPayload.class)));
    }

    @KafkaListener(topics = EventTopics.PAYMENT_FAILED, groupId = GROUP)
    void onPaymentFailed(ConsumerRecord<String, String> rec) {
        consumer.handle(rec, GROUP, env -> saga.onPaymentFailed(env.payloadAs(mapper, PaymentFailedPayload.class)));
    }

    @KafkaListener(topics = EventTopics.RESERVATION_EXPIRED, groupId = GROUP)
    void onReservationExpired(ConsumerRecord<String, String> rec) {
        consumer.handle(rec, GROUP, env -> saga.onReservationExpired(env.payloadAs(mapper, ReservationExpiredPayload.class)));
    }

    @KafkaListener(topics = EventTopics.PAYMENT_VOIDED, groupId = GROUP)
    void onPaymentVoided(ConsumerRecord<String, String> rec) {
        consumer.handle(rec, GROUP, env -> saga.onPaymentVoided(env.payloadAs(mapper, PaymentVoidedPayload.class)));
    }
}
