package com.ecom.inventory.it;

import com.ecom.common.events.EventEnvelope;
import com.ecom.common.events.payload.OrderLinePayload;
import com.ecom.inventory.domain.ReservationStatus;
import com.ecom.inventory.domain.StockLevel;
import com.ecom.inventory.infrastructure.StockLevelRepository;
import com.ecom.testing.Containers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** One shared context for all inventory ITs (identical config => cached). Tests isolate via unique SKUs/orders. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ecom.grpc.server.port=0",
            "ecom.inventory.sweep-enabled=false",
            "ecom.outbox.poll-interval=100ms",
            "management.tracing.enabled=false"
        })
abstract class AbstractInventoryIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry r) {
        Containers.registerPostgres(r);
        Containers.registerKafka(r);
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected StockLevelRepository stockRepo;

    protected static String sku(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected void stock(String sku, int onHand) {
        jdbc.update("INSERT INTO stock_levels (sku, on_hand) VALUES (?, ?)", sku, onHand);
    }

    protected StockLevel level(String sku) {
        return stockRepo.find(sku).orElseThrow();
    }

    protected static List<OrderLinePayload> lines(Object... skuQty) {
        return java.util.stream.IntStream.range(0, skuQty.length / 2)
                .mapToObj(i -> new OrderLinePayload((String) skuQty[2 * i], (Integer) skuQty[2 * i + 1]))
                .toList();
    }

    /** A record as the listener would receive it, with a fresh eventId (i.e. a new envelope). */
    protected ConsumerRecord<String, String> record(String topic, String key, Object payload) {
        return record(EventEnvelope.create(topic, 1, UUID.randomUUID().toString(), "order-service", payload, mapper), key);
    }

    protected ConsumerRecord<String, String> record(EventEnvelope env, String key) {
        try {
            return new ConsumerRecord<>(env.eventType(), 0, 0L, key, mapper.writeValueAsString(env));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected List<EventEnvelope> outboxEvents(String topic, String key) {
        return jdbc.queryForList(
                        "SELECT payload FROM outbox WHERE topic = ? AND message_key = ? ORDER BY id",
                        String.class, topic, key)
                .stream()
                .map(json -> EventEnvelope.parse(mapper, json))
                .toList();
    }

    protected Map<String, ReservationStatus> reservationStatuses(UUID orderId) {
        return jdbc.queryForList("SELECT sku, status FROM stock_reservations WHERE order_id = ?", orderId).stream()
                .collect(Collectors.toMap(
                        r -> (String) r.get("sku"), r -> ReservationStatus.valueOf((String) r.get("status"))));
    }

    protected static List<String> strings(String... s) {
        return Arrays.asList(s);
    }
}
