package com.ecom.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecom.common.events.EventTopics;
import com.ecom.e2e.support.SagaTestBase;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Scenario 1: card authorizes synchronously -> CONFIRMED, stock committed, cart lines removed, captured. */
class HappyPathIT extends SagaTestBase {

    @Test
    void orderIsConfirmedStockCommittedCartClearedAndPaymentCaptured() {
        String skuA = newSku(5, "19.99");
        String skuB = newSku(10, "5.00");
        Shopper s = newShopper();
        Map<String, Integer> lines = new LinkedHashMap<>();
        lines.put(skuA, 2);
        lines.put(skuB, 1);

        String orderId = placeOrder(s, lines, VISA);

        var order = awaitStatus(s, orderId, "CONFIRMED");
        assertThat(order.path("cancelReason").isMissingNode() || order.path("cancelReason").isNull()).isTrue();
        // 2*19.99 + 5.00 = 44.98; VAT 21% = 9.4458 -> 9.45; shipping 4.95
        assertThat(order.path("subtotal").path("amount").asString()).isEqualTo("44.98");
        assertThat(order.path("lines")).hasSize(2);

        awaitReservations(orderId, "COMMITTED");
        awaitStock(skuA, 3, 0);
        awaitStock(skuB, 9, 0);
        awaitPayment(orderId, "CAPTURED");
        await("cart lines removed").atMost(SETTLE).untilAsserted(() -> {
            assertThat(api.cartQty(s.userId(), skuA)).isZero();
            assertThat(api.cartQty(s.userId(), skuB)).isZero();
        });

        var events = sys.events();
        await().atMost(SETTLE).untilAsserted(() -> assertThat(events.count(EventTopics.ORDER_CONFIRMED, orderId)).isEqualTo(1));
        assertThat(events.count(EventTopics.ORDER_CREATED, orderId)).isEqualTo(1);
        assertThat(events.count(EventTopics.INVENTORY_RESERVED, orderId)).isEqualTo(1);
        assertThat(events.count(EventTopics.PAYMENT_AUTHORIZED, orderId)).isEqualTo(1);
        assertThat(events.count(EventTopics.ORDER_CANCELLED, orderId)).isZero();
        await("confirmation notification").atMost(SETTLE).untilAsserted(() ->
                assertThat(db.notificationCount(s.userId(), "ORDER_CONFIRMED")).isEqualTo(1));
    }
}
