package com.ecom.cart.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecom.cart.infrastructure.OrderEventsListener;
import com.ecom.common.events.EventEnvelope;
import com.ecom.common.events.EventTopics;
import com.ecom.common.events.Money;
import com.ecom.common.events.payload.OrderConfirmedPayload;
import com.ecom.common.events.payload.OrderLinePayload;
import com.ecom.testing.Containers;
import com.ecom.testing.KafkaTestSupport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderConfirmedIT extends AbstractCartIT {

    @Autowired OrderEventsListener listener;

    private OrderConfirmedPayload confirmed(String userId, List<OrderLinePayload> lines) {
        return new OrderConfirmedPayload(UUID.randomUUID().toString(), userId, lines, Money.of("1.00", "EUR"));
    }

    private Map<Object, Object> cart(String userId) {
        return redis.opsForHash().entries("cart:user:" + userId);
    }

    @Test
    void decrementsOrderedQuantitiesOnceEvenWhenRedelivered() throws Exception {
        String userId = userId();
        redis.opsForHash().putAll("cart:user:" + userId, Map.of("A", "3", "B", "1", "C", "2"));
        var payload = confirmed(userId, List.of(
                new OrderLinePayload("A", 1), new OrderLinePayload("B", 1), new OrderLinePayload("A", 1),
                new OrderLinePayload("Z", 4)));
        EventEnvelope env = EventEnvelope.create(EventTopics.ORDER_CONFIRMED, 1, "corr", "order-service", payload, mapper);
        var rec = new ConsumerRecord<>(EventTopics.ORDER_CONFIRMED, 0, 0L, payload.orderId(), mapper.writeValueAsString(env));

        listener.onOrderConfirmed(rec);
        listener.onOrderConfirmed(rec);

        assertThat(cart(userId)).containsExactlyInAnyOrderEntriesOf(Map.of("A", "1", "C", "2"));
        String marker = "processed:" + OrderEventsListener.GROUP + ":" + env.eventId();
        assertThat(redis.getExpire(marker, TimeUnit.SECONDS))
                .isBetween(Duration.ofDays(7).toSeconds() - 60, Duration.ofDays(7).toSeconds());
    }

    @Test
    void orderingMoreThanInCartRemovesLineButKeepsOthersAndCartKey() throws Exception {
        String userId = userId();
        redis.opsForHash().putAll("cart:user:" + userId, Map.of("A", "1", "NEW", "1"));
        var payload = confirmed(userId, List.of(new OrderLinePayload("A", 5)));
        EventEnvelope env = EventEnvelope.create(EventTopics.ORDER_CONFIRMED, 1, "corr", "order-service", payload, mapper);

        listener.onOrderConfirmed(
                new ConsumerRecord<>(EventTopics.ORDER_CONFIRMED, 0, 0L, payload.orderId(), mapper.writeValueAsString(env)));

        assertThat(cart(userId)).containsExactlyInAnyOrderEntriesOf(Map.of("NEW", "1"));
    }

    @Test
    void consumesOrderConfirmedFromKafka() {
        String userId = userId();
        redis.opsForHash().putAll("cart:user:" + userId, Map.of("K", "2", "L", "1"));

        KafkaTestSupport.sendPayload(Containers.kafka().getBootstrapServers(), mapper, EventTopics.ORDER_CONFIRMED,
                userId, confirmed(userId, List.of(new OrderLinePayload("K", 2))));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(cart(userId)).containsExactlyInAnyOrderEntriesOf(Map.of("L", "1")));
    }
}
