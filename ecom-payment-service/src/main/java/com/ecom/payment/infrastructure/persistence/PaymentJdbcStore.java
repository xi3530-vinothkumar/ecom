package com.ecom.payment.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

/** Insert-if-absent tables and the per-order lock; joins the caller's (JPA) transaction. */
@Repository
public class PaymentJdbcStore {

    private static final ResultSetExtractor<Void> IGNORE = rs -> null;

    private final JdbcTemplate jdbc;

    public PaymentJdbcStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serializes every payment decision for one order (saga consumer, order-cancelled consumer,
     * webhook) until the surrounding transaction ends. Without it, a cancel could commit between the
     * saga consumer's cancelled-check and its insert, leaving an authorization nobody voids.
     */
    public void lockOrder(UUID orderId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", IGNORE, orderId.toString());
    }

    /** @return true if this call recorded the cancellation (false if it was already recorded) */
    public boolean markOrderCancelled(UUID orderId, Instant at) {
        return jdbc.update(
                "INSERT INTO cancelled_orders (order_id, cancelled_at) VALUES (?, ?) ON CONFLICT (order_id) DO NOTHING",
                orderId, Timestamp.from(at)) == 1;
    }

    public boolean isOrderCancelled(UUID orderId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM cancelled_orders WHERE order_id = ?)", Boolean.class, orderId);
        return Boolean.TRUE.equals(exists);
    }

    /** @return true on first sight of {@code pspEventId}; false for a redelivery */
    public boolean recordWebhookEvent(String pspEventId, String type, Instant at) {
        return jdbc.update(
                "INSERT INTO psp_webhook_events (psp_event_id, type, received_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT (psp_event_id) DO NOTHING",
                pspEventId, type, Timestamp.from(at)) == 1;
    }

    public void recordPaymentMethod(UUID userId, String pspRef, String brand, String last4, Instant at) {
        jdbc.update(
                "INSERT INTO payment_methods (id, user_id, psp_ref, brand, last4, created_at) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (user_id, psp_ref) DO UPDATE SET "
                        + "brand = COALESCE(EXCLUDED.brand, payment_methods.brand), "
                        + "last4 = COALESCE(EXCLUDED.last4, payment_methods.last4)",
                UUID.randomUUID(), userId, pspRef, brand, last4, Timestamp.from(at));
    }
}
