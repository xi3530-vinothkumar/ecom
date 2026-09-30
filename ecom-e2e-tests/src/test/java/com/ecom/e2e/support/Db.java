package com.ecom.e2e.support;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only peeks into the services' own databases for state no public API exposes (reservation rows,
 * payment transaction status). Tests never write here.
 */
public final class Db {

    public record Reservation(String sku, int qty, String status, Instant expiresAt) {}

    public record PaymentTx(String status, String pspReference, String failureReason) {}

    private final EcomSystem sys;

    public Db(EcomSystem sys) {
        this.sys = sys;
    }

    public List<Reservation> reservations(String orderId) {
        try (var c = sys.connect("inventorydb");
                var ps = c.prepareStatement(
                        "SELECT sku, qty, status, expires_at FROM stock_reservations WHERE order_id = ? ORDER BY sku")) {
            ps.setObject(1, UUID.fromString(orderId));
            try (var rs = ps.executeQuery()) {
                List<Reservation> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Reservation(rs.getString(1), rs.getInt(2), rs.getString(3),
                            rs.getTimestamp(4).toInstant()));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<PaymentTx> paymentTransactions(String orderId) {
        try (var c = sys.connect("paymentdb");
                var ps = c.prepareStatement(
                        "SELECT status, psp_reference, failure_reason FROM payment_transactions WHERE order_id = ?")) {
            ps.setObject(1, UUID.fromString(orderId));
            try (var rs = ps.executeQuery()) {
                List<PaymentTx> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new PaymentTx(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<PaymentTx> paymentTx(String orderId) {
        List<PaymentTx> all = paymentTransactions(orderId);
        if (all.size() > 1) {
            throw new AssertionError("More than one payment transaction for order " + orderId + ": " + all);
        }
        return all.stream().findFirst();
    }

    public long orderCount(String userId) {
        try (var c = sys.connect("orderdb");
                var ps = c.prepareStatement("SELECT count(*) FROM orders WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public long notificationCount(String userId, String templateKey) {
        try (var c = sys.connect("notificationdb");
                var ps = c.prepareStatement(
                        "SELECT count(*) FROM notification_log WHERE user_id = ? AND template_key = ? AND status = 'SENT'")) {
            ps.setString(1, userId);
            ps.setString(2, templateKey);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
