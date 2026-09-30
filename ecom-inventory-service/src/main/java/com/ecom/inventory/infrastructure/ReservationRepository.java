package com.ecom.inventory.infrastructure;

import com.ecom.inventory.domain.ReservationLine;
import com.ecom.inventory.domain.ReservationStatus;
import com.ecom.inventory.domain.StockLevel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    /** Advisory-lock namespace (two-int form, disjoint from Flyway's single-bigint locks). */
    private static final int SWEEP_LOCK_NAMESPACE = 0x1AE5;

    private static final String COLUMNS = "order_id, sku, qty, status, expires_at";
    private static final RowMapper<ReservationLine> MAPPER = ReservationRepository::map;

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsForOrder(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM stock_reservations WHERE order_id = ?)", Boolean.class, orderId));
    }

    public void insert(UUID orderId, String sku, int qty, Instant expiresAt) {
        jdbc.update(
                "INSERT INTO stock_reservations (order_id, sku, warehouse_id, qty, status, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                orderId, sku, StockLevel.DEFAULT_WAREHOUSE, qty, ReservationStatus.RESERVED.name(),
                Timestamp.from(expiresAt));
    }

    public List<ReservationLine> findByOrder(UUID orderId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM stock_reservations WHERE order_id = ? ORDER BY sku", MAPPER, orderId);
    }

    /** Locks every line of the order (SKU order), serializing commit / release / sweep per order. */
    public List<ReservationLine> lockByOrder(UUID orderId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM stock_reservations WHERE order_id = ? ORDER BY sku FOR UPDATE",
                MAPPER, orderId);
    }

    /** Guarded transition; returns 0 if the line is no longer in a source state of {@code to}. */
    public int transition(UUID orderId, String sku, ReservationStatus to) {
        Collection<ReservationStatus> from = ReservationStatus.sourcesOf(to);
        return jdbc.update(
                con -> {
                    var ps = con.prepareStatement("UPDATE stock_reservations SET status = ?, updated_at = now()"
                            + " WHERE order_id = ? AND sku = ? AND status = ANY (?)");
                    ps.setString(1, to.name());
                    ps.setObject(2, orderId);
                    ps.setString(3, sku);
                    ps.setArray(4, con.createArrayOf("text", from.stream().map(Enum::name).toArray()));
                    return ps;
                });
    }

    /** Orders with at least one RESERVED line past expiry, oldest first. */
    public List<UUID> findOrdersDue(Instant now, int limit) {
        return jdbc.queryForList(
                "SELECT order_id FROM stock_reservations WHERE status = 'RESERVED' AND expires_at <= ?"
                        + " GROUP BY order_id ORDER BY min(expires_at) LIMIT ?",
                UUID.class, Timestamp.from(now), limit);
    }

    /**
     * Transaction-scoped per-order lock so concurrent sweepers (replicas) never flag the same order twice;
     * returns false immediately if another sweeper holds it.
     */
    public boolean tryLockOrderForSweep(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?, hashtext(?))",
                Boolean.class, SWEEP_LOCK_NAMESPACE, orderId.toString()));
    }

    /**
     * RESERVED -> EXPIRING for the order's due lines. SKIP LOCKED: a line locked by an in-flight commit or
     * release is left to that transaction, which moves it to a terminal state anyway.
     */
    public int markExpiring(UUID orderId, Instant now) {
        return jdbc.update(
                "WITH due AS (SELECT order_id, sku FROM stock_reservations"
                        + " WHERE order_id = ? AND status = 'RESERVED' AND expires_at <= ? FOR UPDATE SKIP LOCKED)"
                        + " UPDATE stock_reservations r SET status = 'EXPIRING', updated_at = now()"
                        + " FROM due WHERE r.order_id = due.order_id AND r.sku = due.sku",
                orderId, Timestamp.from(now));
    }

    /** Orders with a line stuck in EXPIRING since before {@code cutoff}. */
    public long countOrdersExpiringSince(Instant cutoff) {
        Long n = jdbc.queryForObject(
                "SELECT count(DISTINCT order_id) FROM stock_reservations WHERE status = 'EXPIRING' AND updated_at < ?",
                Long.class, Timestamp.from(cutoff));
        return n == null ? 0 : n;
    }

    private static ReservationLine map(ResultSet rs, int i) throws SQLException {
        return new ReservationLine(
                rs.getObject("order_id", UUID.class),
                rs.getString("sku"),
                rs.getInt("qty"),
                ReservationStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("expires_at").toInstant());
    }
}
