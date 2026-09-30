package com.ecom.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecom.common.events.EventTopics;
import com.ecom.e2e.support.Api;
import com.ecom.e2e.support.Db;
import com.ecom.e2e.support.EcomSystem;
import com.ecom.e2e.support.SagaTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Scenarios 4 and 5: 3DS/SCA payments that are completed too late, in time, or right at expiry. */
class ThreeDSecureIT extends SagaTestBase {

    /** Waits until Payment parked the authorization as REQUIRES_ACTION and the client can see the action. */
    private static String awaitRequiresAction(Shopper s, String orderId) {
        Db.PaymentTx tx = awaitPayment(orderId, "REQUIRES_ACTION");
        await("paymentAction exposed").atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var order = api.order(s.userId(), orderId);
            assertThat(order.path("status").asText()).isEqualTo("PENDING");
            assertThat(order.path("paymentAction").path("clientSecret").asText()).startsWith(tx.pspReference());
        });
        return tx.pspReference();
    }

    @Test
    void threeDsNotCompletedBeforeTtlTimesOutThenLateAuthorizationIsVoided() {
        String sku = newSku(4, "30.00");
        Shopper s = newShopper();
        String orderId = placeOrder(s, Map.of(sku, 3), THREE_DS);
        String pspRef = awaitRequiresAction(s, orderId);
        awaitStock(sku, 4, 3);

        var order = awaitStatus(s, orderId, "CANCELLED");
        assertThat(order.path("cancelReason").asText()).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(sys.events().count(EventTopics.RESERVATION_EXPIRED, orderId)).isEqualTo(1);
        awaitReservations(orderId, "RELEASED");
        awaitStock(sku, 4, 0);

        // the customer finishes 3DS after the order was cancelled: the late authorization must be voided.
        // Payment may already have cancelled the intent at the PSP (409) - either way it ends VOIDED.
        Api.Resp late = api.completeAction(pspRef, "succeeded");
        assertThat(late.status()).as(late.raw()).isIn(200, 409);
        awaitPayment(orderId, "VOIDED");
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).untilAsserted(() -> {
            assertThat(api.order(s.userId(), orderId).path("status").asText()).isEqualTo("CANCELLED");
            assertThat(db.paymentTx(orderId).orElseThrow().status()).isEqualTo("VOIDED");
            assertThat(api.stock(sku)).isEqualTo(new Api.Stock(4, 0, 4));
        });
        assertThat(sys.events().count(EventTopics.ORDER_CONFIRMED, orderId)).isZero();
    }

    @Test
    void threeDsCompletedInTimeConfirmsAndCommitsStock() {
        String sku = newSku(2, "49.00");
        Shopper s = newShopper();
        String orderId = placeOrder(s, Map.of(sku, 1), THREE_DS);
        String pspRef = awaitRequiresAction(s, orderId);

        api.completeAction(pspRef, "succeeded").expect(200);

        awaitStatus(s, orderId, "CONFIRMED");
        awaitReservations(orderId, "COMMITTED");
        awaitStock(sku, 1, 0);
        awaitPayment(orderId, "CAPTURED");
    }

    /**
     * Race variant of scenario 5: the 3DS completion lands around the reservation expiry (sweep marks
     * EXPIRING + ReservationExpired vs. webhook -> PaymentAuthorized). Whatever wins, the saga must
     * converge on CONFIRMED+COMMITTED+CAPTURED or CANCELLED+RELEASED+VOIDED, with stock exact.
     */
    @Test
    void threeDsCompletedAroundExpiryAlwaysConverges() throws Exception {
        long[] offsetsMs = {-1500, -500, 0, 500, 1000, 1500, 2000, 3000};
        ExecutorService pool = Executors.newFixedThreadPool(offsetsMs.length);
        try {
            List<CompletableFuture<String>> outcomes = new ArrayList<>();
            for (long offset : offsetsMs) {
                outcomes.add(CompletableFuture.supplyAsync(() -> raceOnce(offset), pool));
            }
            List<String> results = new ArrayList<>();
            for (var f : outcomes) {
                results.add(f.get());
            }
            System.out.println("[e2e] 3DS/expiry race outcomes by offset " + java.util.Arrays.toString(offsetsMs)
                    + ": " + results);
        } finally {
            pool.shutdownNow();
        }
    }

    private static String raceOnce(long offsetMs) {
        int onHand = 2;
        String sku = newSku(onHand, "15.00");
        Shopper s = newShopper();
        String orderId = placeOrder(s, Map.of(sku, 1), THREE_DS);
        String pspRef = awaitRequiresAction(s, orderId);
        Instant expiresAt = db.reservations(orderId).get(0).expiresAt();
        long waitMs = Duration.between(Instant.now(), expiresAt.plusMillis(offsetMs)).toMillis();
        assertThat(waitMs).as("reservation TTL too short for the race setup").isLessThan(
                EcomSystem.RESERVATION_TTL.toMillis() + 5000);
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        Api.Resp r = api.completeAction(pspRef, "succeeded");
        assertThat(r.status()).as(r.raw()).isIn(200, 409);

        var order = awaitTerminal(s, orderId);
        String status = order.path("status").asText();
        if (status.equals("CONFIRMED")) {
            awaitReservations(orderId, "COMMITTED");
            awaitPayment(orderId, "CAPTURED");
            awaitStock(sku, onHand - 1, 0);
        } else {
            assertThat(order.path("cancelReason").asText()).isEqualTo("PAYMENT_TIMEOUT");
            awaitReservations(orderId, "RELEASED");
            awaitPayment(orderId, "VOIDED");
            awaitStock(sku, onHand, 0);
        }
        // and it stays converged (no late flip in either direction)
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).untilAsserted(() -> {
            assertThat(api.order(s.userId(), orderId).path("status").asText()).isEqualTo(status);
            var stock = api.stock(sku);
            assertThat(stock.onHand()).isEqualTo(status.equals("CONFIRMED") ? onHand - 1 : onHand);
            assertThat(stock.reserved()).isZero();
        });
        return offsetMs + "ms=" + status + "(completeAction " + r.status() + ")";
    }
}
