package com.ecom.notification.infrastructure;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.payload.OrderCancelledPayload;
import com.ecom.common.events.payload.OrderConfirmedPayload;
import com.ecom.common.events.payload.UserRegisteredPayload;
import com.ecom.notification.application.NotificationEventHandler;
import com.ecom.platform.kafka.IdempotentConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class NotificationEventListener {

    public static final String GROUP = "notification-service-cg";

    private final IdempotentConsumer consumer;
    private final ObjectMapper mapper;
    private final NotificationEventHandler handler;

    public NotificationEventListener(IdempotentConsumer consumer, NotificationEventHandler handler) {
        this.consumer = consumer;
        this.mapper = consumer.mapper();
        this.handler = handler;
    }

    @KafkaListener(topics = EventTopics.USER_REGISTERED, groupId = GROUP)
    void onUserRegistered(ConsumerRecord<String, String> rec) {
        consumer.handle(rec, GROUP, env ->
                handler.onUserRegistered(env.eventId(), env.payloadAs(mapper, UserRegisteredPayload.class)));
    }

    @KafkaListener(topics = EventTopics.ORDER_CONFIRMED, groupId = GROUP)
    void onOrderConfirmed(ConsumerRecord<String, String> rec) {
        consumer.handle(rec, GROUP, env ->
                handler.onOrderConfirmed(env.eventId(), env.payloadAs(mapper, OrderConfirmedPayload.class)));
    }

    @KafkaListener(topics = EventTopics.ORDER_CANCELLED, groupId = GROUP)
    void onOrderCancelled(ConsumerRecord<String, String> rec) {
        consumer.handle(rec, GROUP, env ->
                handler.onOrderCancelled(env.eventId(), env.payloadAs(mapper, OrderCancelledPayload.class)));
    }
}
