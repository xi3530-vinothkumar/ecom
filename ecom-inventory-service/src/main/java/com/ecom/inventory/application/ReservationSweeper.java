package com.ecom.inventory.application;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.payload.ReservationExpiredPayload;
import com.ecom.inventory.config.InventoryProperties;
import com.ecom.inventory.infrastructure.ReservationRepository;
import com.ecom.platform.kafka.OutboxWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Flags overdue reservations EXPIRING and asks Order to decide (reservation-expired). It never releases
 * stock: releasing here would race a slow 3DS authorization and oversell (PLAN §3).
 */
@Component
public class ReservationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationSweeper.class);
    private static final int MAX_BATCHES_PER_RUN = 50;

    private final ReservationRepository reservations;
    private final OutboxWriter outbox;
    private final InventoryMetrics metrics;
    private final InventoryProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ReservationSweeper(
            ReservationRepository reservations,
            OutboxWriter outbox,
            InventoryMetrics metrics,
            InventoryProperties props,
            Clock clock,
            PlatformTransactionManager txm) {
        this.reservations = reservations;
        this.outbox = outbox;
        this.metrics = metrics;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txm);
    }

    private record Batch(int candidates, int expired) {}

    /** @return number of orders flagged EXPIRING in this run. */
    public int sweep() {
        int total = 0;
        try {
            for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
                Batch batch = tx.execute(s -> sweepBatch());
                total += batch.expired();
                // stop when drained, or when every remaining candidate is owned by another sweeper
                if (batch.candidates() < props.sweepBatchSize() || batch.expired() == 0) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Reservation sweep failed; will retry next run", e);
        }
        if (total > 0) {
            log.info("Reservation sweep flagged {} order(s) EXPIRING", total);
        }
        return total;
    }

    private Batch sweepBatch() {
        Instant now = clock.instant();
        List<UUID> due = reservations.findOrdersDue(now, props.sweepBatchSize());
        int expired = 0;
        for (UUID orderId : due) {
            if (!reservations.tryLockOrderForSweep(orderId)) {
                continue;
            }
            if (reservations.markExpiring(orderId, now) > 0) {
                outbox.publish(
                        EventTopics.RESERVATION_EXPIRED, orderId.toString(),
                        new ReservationExpiredPayload(orderId.toString()));
                metrics.reservationExpired();
                expired++;
            }
        }
        return new Batch(due.size(), expired);
    }
}
