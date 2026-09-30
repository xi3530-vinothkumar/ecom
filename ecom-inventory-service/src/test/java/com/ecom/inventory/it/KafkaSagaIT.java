package com.ecom.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.Money;
import com.ecom.common.events.payload.OrderConfirmedPayload;
import com.ecom.common.events.payload.OrderCreatedPayload;
import com.ecom.testing.Containers;
import com.ecom.testing.KafkaTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Full path through real Kafka: listener -> idempotent consumer -> DB -> outbox relay -> topic. */
class KafkaSagaIT extends AbstractInventoryIT {

    @Test
    void orderCreatedOnKafkaYieldsInventoryReservedOnKafkaThenConfirmCommits() {
        String a = sku("A");
        stock(a, 3);
        String orderId = UUID.randomUUID().toString();
        String bootstrap = Containers.kafka().getBootstrapServers();

        KafkaTestSupport.sendPayload(bootstrap, mapper, EventTopics.ORDER_CREATED, orderId,
                new OrderCreatedPayload(orderId, "user-k", lines(a, 2)));

        var reserved = KafkaTestSupport.await(bootstrap, mapper, EventTopics.INVENTORY_RESERVED,
                e -> orderId.equals(e.payload().path("orderId").asText()), Duration.ofSeconds(30));
        assertThat(reserved).hasSize(1);
        assertThat(reserved.getFirst().producerService()).isEqualTo("inventory-service");
        var stockChanged = KafkaTestSupport.await(bootstrap, mapper, EventTopics.STOCK_LEVEL_CHANGED,
                e -> a.equals(e.payload().path("sku").asText()), Duration.ofSeconds(30));
        assertThat(stockChanged.getFirst().payload().path("availableQty").asInt()).isEqualTo(1);

        KafkaTestSupport.sendPayload(bootstrap, mapper, EventTopics.ORDER_CONFIRMED, orderId,
                new OrderConfirmedPayload(orderId, "user-k", lines(a, 2), Money.of("1.00", "EUR")));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(level(a).onHand()).isEqualTo(1);
            assertThat(level(a).reserved()).isZero();
        });
    }
}
