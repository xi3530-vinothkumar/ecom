package com.ecom.order.infrastructure;

import com.ecom.order.domain.SagaStep;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SagaStateRepository {

    private final JdbcTemplate jdbc;

    public SagaStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Upserts the last observed step. A no-op for unknown order ids, so a stray event for an order
     * this service never created cannot fail the consumer on the foreign key.
     */
    public void record(UUID orderId, SagaStep step, Instant at) {
        jdbc.update("""
                INSERT INTO order_saga_state (order_id, step, updated_at)
                SELECT id, ?, ? FROM orders WHERE id = ?
                ON CONFLICT (order_id) DO UPDATE SET step = EXCLUDED.step, updated_at = EXCLUDED.updated_at
                """, step.name(), Timestamp.from(at), orderId);
    }

    public Optional<SagaStep> find(UUID orderId) {
        return jdbc.query("SELECT step FROM order_saga_state WHERE order_id = ?",
                        (rs, i) -> SagaStep.valueOf(rs.getString(1)), orderId)
                .stream()
                .findFirst();
    }
}
