package com.ecom.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecom.common.events.EventTopics;
import com.ecom.e2e.support.SagaTestBase;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Scenarios 2 and 3: the two synchronous compensation branches. */
class CompensationIT extends SagaTestBase {

    @Test
    void outOfStockCancelsWithoutAttemptingPayment() {
        String sku = newSku(1, "10.00");
        Shopper s = newShopper();

        String orderId = placeOrder(s, Map.of(sku, 2), VISA);

        var order = awaitStatus(s, orderId, "CANCELLED");
        assertThat(order.path("cancelReason").asString()).isEqualTo("OUT_OF_STOCK");
        await().atMost(SETTLE).untilAsserted(() ->
                assertThat(sys.events().count(EventTopics.INVENTORY_RESERVATION_FAILED, orderId)).isEqualTo(1));
        // give Payment a chance to (wrongly) act, then assert it never did
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(db.paymentTransactions(orderId)).isEmpty();
            assertThat(db.reservations(orderId)).isEmpty();
        });
        assertThat(api.stock(sku).onHand()).isEqualTo(1);
        assertThat(api.stock(sku).reserved()).isZero();
        assertThat(sys.events().count(EventTopics.INVENTORY_RESERVED, orderId)).isZero();
        // cart keeps its lines when the order is cancelled
        assertThat(api.cartQty(s.userId(), sku)).isEqualTo(2);
    }

    @Test
    void declinedPaymentCancelsAndReleasesReservation() {
        String sku = newSku(3, "12.50");
        Shopper s = newShopper();

        String orderId = placeOrder(s, Map.of(sku, 2), DECLINED);

        var order = awaitStatus(s, orderId, "CANCELLED");
        assertThat(order.path("cancelReason").asString()).isEqualTo("PAYMENT_DECLINED");
        var tx = awaitPayment(orderId, "FAILED");
        assertThat(tx.pspReference()).isNotBlank();
        awaitReservations(orderId, "RELEASED");
        awaitStock(sku, 3, 0);
        assertThat(sys.events().count(EventTopics.PAYMENT_FAILED, orderId)).isEqualTo(1);
        assertThat(sys.events().count(EventTopics.ORDER_CONFIRMED, orderId)).isZero();
    }
}
