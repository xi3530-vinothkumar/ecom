package com.ecom.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

/**
 * Shared base for all saga scenarios: one started {@link EcomSystem} per JVM, plus helpers that set up
 * an isolated world per scenario (own user, own SKUs) through the public/admin REST APIs.
 */
@ExtendWith(EcomSystemExtension.class)
@Timeout(value = 5, unit = java.util.concurrent.TimeUnit.MINUTES)
public abstract class SagaTestBase {

    public static final String VISA = "pm_card_visa";
    public static final String DECLINED = "pm_card_chargeDeclined";
    public static final String THREE_DS = "pm_card_threeDSecure2Required";

    /** Upper bound for any saga to settle once its trigger happened (TTL + sweep + slack for timeout cases). */
    protected static final Duration SETTLE = EcomSystem.RESERVATION_TTL.plusSeconds(40);

    protected static EcomSystem sys;
    protected static Api api;
    protected static Db db;
    private static String categoryId;

    public record Shopper(String userId, String addressId) {}

    @BeforeAll
    static void wire() {
        sys = EcomSystem.get();
        api = new Api(sys);
        db = new Db(sys);
    }

    protected static synchronized String category() {
        if (categoryId == null) {
            categoryId = api.createCategory("E2E " + UUID.randomUUID());
        }
        return categoryId;
    }

    /** Creates a product with one variant (unique SKU) and sets its on-hand stock. */
    protected static String newSku(int onHand, String price) {
        String sku = "E2E-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase();
        api.createProduct(category(), sku, price);
        api.setStock(sku, onHand);
        return sku;
    }

    protected static Shopper newShopper() {
        String userId = api.register("e2e-" + UUID.randomUUID() + "@example.com");
        return new Shopper(userId, api.addAddress(userId));
    }

    /** Fills the shopper's cart and checks out with a fresh Idempotency-Key; returns the new orderId. */
    protected static String placeOrder(Shopper s, Map<String, Integer> lines, String paymentMethod) {
        lines.forEach((sku, qty) -> api.setCartItem(s.userId(), sku, qty));
        var resp = api.checkout(s.userId(), UUID.randomUUID().toString(), paymentMethod, s.addressId()).expect(202);
        assertThat(resp.text("status")).isEqualTo("PENDING");
        return resp.text("orderId");
    }

    protected static JsonNode awaitStatus(Shopper s, String orderId, String status) {
        AtomicReference<JsonNode> last = new AtomicReference<>();
        await("order " + orderId + " -> " + status).atMost(SETTLE).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    last.set(api.order(s.userId(), orderId));
                    assertThat(last.get().path("status").asString()).as("order %s", last.get()).isEqualTo(status);
                });
        return last.get();
    }

    protected static JsonNode awaitTerminal(Shopper s, String orderId) {
        AtomicReference<JsonNode> last = new AtomicReference<>();
        await("order " + orderId + " terminal").atMost(SETTLE).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    last.set(api.order(s.userId(), orderId));
                    assertThat(last.get().path("status").asString()).isNotEqualTo("PENDING");
                });
        return last.get();
    }

    protected static Db.PaymentTx awaitPayment(String orderId, String status) {
        AtomicReference<Db.PaymentTx> last = new AtomicReference<>();
        await("payment of " + orderId + " -> " + status).atMost(SETTLE).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    var tx = db.paymentTx(orderId);
                    assertThat(tx).as("payment tx of %s", orderId).isPresent();
                    last.set(tx.get());
                    assertThat(tx.get().status()).isEqualTo(status);
                });
        return last.get();
    }

    protected static void awaitReservations(String orderId, String status) {
        await("reservations of " + orderId + " -> " + status).atMost(SETTLE).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(db.reservations(orderId))
                        .isNotEmpty()
                        .allSatisfy(r -> assertThat(r.status()).isEqualTo(status)));
    }

    protected static void awaitStock(String sku, int onHand, int reserved) {
        await("stock of " + sku).atMost(SETTLE).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(api.stock(sku)).isEqualTo(new Api.Stock(onHand, reserved, onHand - reserved)));
    }
}
