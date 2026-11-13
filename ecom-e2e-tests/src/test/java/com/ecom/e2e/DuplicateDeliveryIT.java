package com.ecom.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecom.common.events.EventTopics;
import com.ecom.e2e.support.Api;
import com.ecom.e2e.support.EventLog;
import com.ecom.e2e.support.Json;
import com.ecom.e2e.support.SagaTestBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Scenario 6: at-least-once delivery. Captured saga events are re-published verbatim (same eventId)
 * after the order settled; no consumer may act twice. A re-sent order-created with a NEW eventId (dedup
 * bypassed) must still not reserve twice (state guard / reservation PK).
 */
class DuplicateDeliveryIT extends SagaTestBase {

    private static final List<String> REPLAYED = List.of(
            EventTopics.ORDER_CREATED, EventTopics.INVENTORY_RESERVED, EventTopics.PAYMENT_AUTHORIZED,
            EventTopics.ORDER_CONFIRMED);

    @Test
    void duplicateDeliveriesOfEveryHappyPathEventAreNoOps() {
        String sku = newSku(6, "8.00");
        Shopper s = newShopper();
        String orderId = placeOrder(s, Map.of(sku, 2), VISA);
        awaitStatus(s, orderId, "CONFIRMED");
        awaitReservations(orderId, "COMMITTED");
        awaitPayment(orderId, "CAPTURED");
        awaitStock(sku, 4, 0);
        await().atMost(SETTLE).untilAsserted(() ->
                assertThat(db.notificationCount(s.userId(), "ORDER_CONFIRMED")).isEqualTo(1));

        EventLog events = sys.events();
        await().atMost(SETTLE).untilAsserted(() -> REPLAYED.forEach(t ->
                assertThat(events.count(t, orderId)).as(t).isEqualTo(1)));

        // user puts the same SKU back in the cart after checkout: a duplicate order-confirmed must not
        // remove it again
        api.setCartItem(s.userId(), sku, 2);

        for (String topic : REPLAYED) {
            events.republish(events.forOrder(topic, orderId).get(0));
        }

        await().during(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(12)).untilAsserted(() -> {
            assertThat(api.stock(sku)).isEqualTo(new Api.Stock(4, 0, 4));
            assertThat(db.reservations(orderId)).hasSize(1).allSatisfy(r -> {
                assertThat(r.status()).isEqualTo("COMMITTED");
                assertThat(r.qty()).isEqualTo(2);
            });
            assertThat(db.paymentTransactions(orderId)).hasSize(1).allSatisfy(tx ->
                    assertThat(tx.status()).isEqualTo("CAPTURED"));
            assertThat(api.order(s.userId(), orderId).path("status").asString()).isEqualTo("CONFIRMED");
            assertThat(api.cartQty(s.userId(), sku)).isEqualTo(2);
            assertThat(db.notificationCount(s.userId(), "ORDER_CONFIRMED")).isEqualTo(1);
            // no consumer re-emitted anything: each downstream event still exists exactly once
            // (the replayed copies themselves are counted, hence 2 for the replayed topics)
            for (String topic : REPLAYED) {
                assertThat(events.count(topic, orderId)).as(topic).isEqualTo(2);
            }
            assertThat(events.count(EventTopics.INVENTORY_RESERVATION_FAILED, orderId)).isZero();
            assertThat(events.count(EventTopics.PAYMENT_FAILED, orderId)).isZero();
            assertThat(events.count(EventTopics.ORDER_CANCELLED, orderId)).isZero();
        });
    }

    @Test
    void redeliveredOrderCreatedWithNewEventIdDoesNotReserveTwice() throws Exception {
        String sku = newSku(5, "3.00");
        Shopper s = newShopper();
        String orderId = placeOrder(s, Map.of(sku, 2), THREE_DS);
        // keep the order PENDING (3DS outstanding) so the reservation is still live when the copy arrives
        awaitPayment(orderId, "REQUIRES_ACTION");
        awaitStock(sku, 5, 2);

        EventLog events = sys.events();
        EventLog.Rec original = events.forOrder(EventTopics.ORDER_CREATED, orderId).get(0);
        ObjectNode copy = (ObjectNode) Json.MAPPER.readTree(original.rawValue());
        copy.put("eventId", UUID.randomUUID().toString());
        events.send(original.topic(), original.key(), Json.MAPPER.writeValueAsString(copy));

        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(api.stock(sku)).isEqualTo(new Api.Stock(5, 2, 3));
            assertThat(db.reservations(orderId)).hasSize(1);
            assertThat(db.paymentTransactions(orderId)).hasSize(1);
            assertThat(events.count(EventTopics.INVENTORY_RESERVATION_FAILED, orderId)).isZero();
        });

        String pspRef = db.paymentTx(orderId).orElseThrow().pspReference();
        api.completeAction(pspRef, "succeeded").expect(200);
        awaitStatus(s, orderId, "CONFIRMED");
        awaitStock(sku, 3, 0);
    }
}
