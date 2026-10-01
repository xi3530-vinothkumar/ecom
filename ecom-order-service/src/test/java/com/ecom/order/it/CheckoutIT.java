package com.ecom.order.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.common.events.EventEnvelope;
import com.ecom.common.events.EventTopics;
import com.ecom.testing.Containers;
import com.ecom.testing.KafkaTestSupport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class CheckoutIT extends AbstractOrderIT {

    @Test
    void happyPathPersistsPendingOrderAndEmitsOrderCreated() {
        String user = userWithCart();

        ResponseEntity<Map> res = checkout(user, UUID.randomUUID().toString(), "pm_card_visa", "home");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(res.getBody()).containsEntry("status", "PENDING");
        UUID orderId = UUID.fromString((String) res.getBody().get("orderId"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(row.get("user_id")).isEqualTo(user);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat((BigDecimal) row.get("subtotal_amount")).isEqualByComparingTo("25.50");
        assertThat((BigDecimal) row.get("vat_amount")).isEqualByComparingTo("5.36");
        assertThat((BigDecimal) row.get("shipping_amount")).isEqualByComparingTo("4.95");
        assertThat((BigDecimal) row.get("total_amount")).isEqualByComparingTo("35.81");
        assertThat(row.get("currency")).isEqualTo("EUR");
        assertThat(row.get("ship_city")).isEqualTo("Amsterdam");
        assertThat(row.get("payment_method_ref")).isEqualTo("pm_card_visa");
        assertThat(jdbc.queryForList("SELECT sku, name, quantity FROM order_lines WHERE order_id = ? ORDER BY sku", orderId))
                .extracting(m -> m.get("sku") + "x" + m.get("quantity"))
                .containsExactly("SKU-Ax2", "SKU-Bx1");
        assertThat(sagaStep(orderId)).isEqualTo("ORDER_CREATED");

        List<EventEnvelope> created = KafkaTestSupport.await(Containers.kafka().getBootstrapServers(), mapper,
                EventTopics.ORDER_CREATED, e -> orderId.toString().equals(e.payload().path("orderId").asString()), TIMEOUT);
        assertThat(created).hasSize(1);
        EventEnvelope env = created.getFirst();
        assertThat(env.producerService()).isEqualTo("order-service");
        assertThat(env.payload().path("userId").asString()).isEqualTo(user);
        assertThat(env.payload().path("lines")).hasSize(2);
        assertThat(env.payload().path("lines").get(0).path("sku").asString()).isEqualTo("SKU-A");
        assertThat(env.payload().path("lines").get(0).path("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void replayWithSameKeyAndBodyReturnsExistingOrder() {
        String user = userWithCart();
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map> first = checkout(user, key, "pm_card_visa", "home");
        FakePeers.cart(user, "SKU-B", 5); // cart changes must not matter for a replay
        ResponseEntity<Map> second = checkout(user, key, "pm_card_visa", "home");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("orderId")).isEqualTo(first.getBody().get("orderId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = ?", Integer.class, user)).isEqualTo(1);
        assertThat(outboxCount(EventTopics.ORDER_CREATED, UUID.fromString((String) first.getBody().get("orderId")))).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIs422() {
        String user = userWithCart();
        FakePeers.address(user, "work");
        String key = UUID.randomUUID().toString();

        checkout(user, key, "pm_card_visa", "home");
        ResponseEntity<Map> mismatch = checkout(user, key, "pm_card_visa", "work");

        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = ?", Integer.class, user)).isEqualTo(1);
    }

    @Test
    void sameKeyIsScopedPerUser() {
        String alice = userWithCart();
        String bob = userWithCart();
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map> a = checkout(alice, key, "pm_card_visa", "home");
        ResponseEntity<Map> b = checkout(bob, key, "pm_card_visa", "home");

        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(a.getBody().get("orderId")).isNotEqualTo(b.getBody().get("orderId"));
    }

    @Test
    void concurrentDuplicatesYieldOneOrder() throws Exception {
        String user = userWithCart();
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<ResponseEntity<Map>>> calls = IntStream.range(0, 6)
                    .<Callable<ResponseEntity<Map>>>mapToObj(i -> () -> checkout(user, key, "pm_card_visa", "home"))
                    .toList();
            List<ResponseEntity<Map>> results = pool.invokeAll(calls).stream().map(FakeFutures::join).toList();

            assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode()).isIn(HttpStatus.ACCEPTED, HttpStatus.OK));
            Set<Object> ids = results.stream().map(r -> r.getBody().get("orderId")).collect(Collectors.toSet());
            assertThat(ids).hasSize(1);
            assertThat(results.stream().filter(r -> r.getStatusCode() == HttpStatus.ACCEPTED)).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = ?", Integer.class, user)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void missingIdempotencyKeyIs400() {
        String user = userWithCart();
        assertThat(checkout(user, null, "pm_card_visa", "home").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidBodyIs400() {
        String user = userWithCart();
        assertThat(checkout(user, "k-" + UUID.randomUUID(), "", "home").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anonymousCheckoutIs401() {
        assertThat(checkout(null, "k", "pm_card_visa", "home").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void emptyCartIs422AndPersistsNothing() {
        String user = newUser();
        FakePeers.address(user, "home");

        ResponseEntity<Map> res = checkout(user, UUID.randomUUID().toString(), "pm_card_visa", "home");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = ?", Integer.class, user)).isZero();
    }

    @Test
    void missingOrInactiveProductIs422() {
        String user = userWithCart();
        FakePeers.product("SKU-OFF", "Retired", "1.00", false);
        FakePeers.cart(user, "SKU-A", 1, "SKU-OFF", 1, "SKU-GONE", 1);

        ResponseEntity<Map> res = checkout(user, UUID.randomUUID().toString(), "pm_card_visa", "home");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) res.getBody().get("detail")).contains("SKU-OFF").contains("SKU-GONE").doesNotContain("SKU-A,");
    }

    @Test
    void unknownShippingAddressIs422() {
        String user = userWithCart();
        ResponseEntity<Map> res = checkout(user, UUID.randomUUID().toString(), "pm_card_visa", "nope");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    static final class FakeFutures {
        static <T> T join(Future<T> f) {
            try {
                return f.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
