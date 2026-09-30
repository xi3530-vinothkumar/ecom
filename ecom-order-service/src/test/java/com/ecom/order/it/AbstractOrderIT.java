package com.ecom.order.it;

import static org.awaitility.Awaitility.await;

import com.ecom.common.events.EventEnvelope;
import com.ecom.order.OrderServiceApplication;
import com.ecom.order.infrastructure.OrderSagaListener;
import com.ecom.testing.Containers;
import com.ecom.testing.KafkaTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** One shared context for every IT (same config => cached). Each test uses its own fresh user id. */
@SpringBootTest(
        classes = OrderServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ecom.grpc.server.port=0",
            "ecom.outbox.poll-interval=100ms",
            "ecom.order.pending-stale-refresh=1h",
            "management.tracing.export.enabled=false"
        })
@AutoConfigureTestRestTemplate
abstract class AbstractOrderIT {

    static final Duration TIMEOUT = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Containers.registerPostgres(r);
        Containers.registerKafka(r);
        r.add("ecom.grpc.clients.cart.address", () -> FakePeers.address(FakePeers.cart));
        r.add("ecom.grpc.clients.catalog.address", () -> FakePeers.address(FakePeers.catalog));
        r.add("ecom.grpc.clients.user.address", () -> FakePeers.address(FakePeers.user));
        r.add("ecom.grpc.clients.payment.address", () -> FakePeers.address(FakePeers.payment));
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    static String newUser() {
        return "user-" + UUID.randomUUID();
    }

    /** User with address "home" and a cart of 2 x SKU-A (10.00) + 1 x SKU-B (5.50). */
    static String userWithCart() {
        String user = newUser();
        FakePeers.product("SKU-A", "Mug", "10.00", true);
        FakePeers.product("SKU-B", "Tea", "5.50", true);
        FakePeers.cart(user, "SKU-A", 2, "SKU-B", 1);
        FakePeers.address(user, "home");
        return user;
    }

    ResponseEntity<Map> checkout(String userId, String idempotencyKey, String paymentMethodRef, String addressId) {
        HttpHeaders h = headers(userId);
        if (idempotencyKey != null) {
            h.set("Idempotency-Key", idempotencyKey);
        }
        h.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("paymentMethodRef", paymentMethodRef, "shippingAddressId", addressId);
        return http.exchange("/orders", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    UUID placeOrder(String userId) {
        ResponseEntity<Map> res = checkout(userId, UUID.randomUUID().toString(), "pm_card_visa", "home");
        if (res.getStatusCode() != HttpStatus.ACCEPTED) {
            throw new IllegalStateException("checkout failed: " + res);
        }
        return UUID.fromString((String) res.getBody().get("orderId"));
    }

    ResponseEntity<Map> get(String userId, String path) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(userId)), Map.class);
    }

    static HttpHeaders headers(String userId) {
        HttpHeaders h = new HttpHeaders();
        if (userId != null) {
            h.set("X-User-Id", userId);
        }
        return h;
    }

    EventEnvelope send(String topic, String key, Object payload) {
        return KafkaTestSupport.sendPayload(Containers.kafka().getBootstrapServers(), mapper, topic, key, payload);
    }

    void resend(String topic, String key, EventEnvelope env) {
        KafkaTestSupport.send(Containers.kafka().getBootstrapServers(), mapper, topic, key, env);
    }

    void awaitProcessed(EventEnvelope env) {
        await().atMost(TIMEOUT).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer = ?",
                Integer.class, env.eventId(), OrderSagaListener.GROUP) == 1);
    }

    String status(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    String cancelReason(UUID orderId) {
        return jdbc.queryForObject("SELECT cancel_reason FROM orders WHERE id = ?", String.class, orderId);
    }

    String sagaStep(UUID orderId) {
        return jdbc.queryForObject("SELECT step FROM order_saga_state WHERE order_id = ?", String.class, orderId);
    }

    int outboxCount(String topic, UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE topic = ? AND message_key = ?",
                Integer.class, topic, orderId.toString());
    }

    void awaitStatus(UUID orderId, String expected) {
        await().atMost(TIMEOUT).until(() -> expected.equals(status(orderId)));
    }
}
