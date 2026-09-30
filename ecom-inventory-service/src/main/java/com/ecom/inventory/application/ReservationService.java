package com.ecom.inventory.application;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.payload.InventoryReservationFailedPayload;
import com.ecom.common.events.payload.InventoryReservedPayload;
import com.ecom.inventory.config.InventoryProperties;
import com.ecom.inventory.domain.ReservationLine;
import com.ecom.inventory.domain.ReservationStatus;
import com.ecom.inventory.domain.SkuQuantity;
import com.ecom.inventory.domain.StockAllocation;
import com.ecom.inventory.domain.StockLevel;
import com.ecom.inventory.infrastructure.ReservationRepository;
import com.ecom.inventory.infrastructure.StockLevelRepository;
import com.ecom.platform.kafka.OutboxWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saga participant: reserve on order-created, commit on order-confirmed, release on order-cancelled.
 * Every method runs in the idempotent consumer's transaction, so state changes and emitted events
 * commit atomically.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    public enum ReserveOutcome { RESERVED, FAILED, ALREADY_RESERVED }

    private final StockLevelRepository stock;
    private final ReservationRepository reservations;
    private final OutboxWriter outbox;
    private final StockEvents stockEvents;
    private final InventoryMetrics metrics;
    private final InventoryProperties props;
    private final Clock clock;

    public ReservationService(
            StockLevelRepository stock,
            ReservationRepository reservations,
            OutboxWriter outbox,
            StockEvents stockEvents,
            InventoryMetrics metrics,
            InventoryProperties props,
            Clock clock) {
        this.stock = stock;
        this.reservations = reservations;
        this.outbox = outbox;
        this.stockEvents = stockEvents;
        this.metrics = metrics;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public ReserveOutcome reserve(UUID orderId, List<SkuQuantity> lines) {
        SortedMap<String, Integer> requested = SkuQuantity.aggregate(lines);
        Map<String, StockLevel> locked = stock.lockForUpdate(requested.keySet());

        // Checked only after the stock locks are held: a concurrent redelivery of the same order (with a
        // different eventId) has either committed its rows by now, or is still waiting behind us.
        if (reservations.existsForOrder(orderId)) {
            log.info("Order {} already has reservations; ignoring redelivered order-created", orderId);
            return ReserveOutcome.ALREADY_RESERVED;
        }

        List<String> shortSkus = StockAllocation.shortSkus(requested, locked);
        if (!shortSkus.isEmpty()) {
            log.info("Reservation failed for order {}: short SKUs {}", orderId, shortSkus);
            outbox.publish(
                    EventTopics.INVENTORY_RESERVATION_FAILED,
                    orderId.toString(),
                    new InventoryReservationFailedPayload(orderId.toString(), shortSkus));
            metrics.reservationFailed();
            return ReserveOutcome.FAILED;
        }

        Instant expiresAt = clock.instant().plus(props.reservationTtl()).truncatedTo(ChronoUnit.MICROS);
        requested.forEach((sku, qty) -> {
            StockLevel updated = stock.adjust(sku, 0, qty);
            reservations.insert(orderId, sku, qty, expiresAt);
            stockEvents.availabilityChanged(updated);
        });
        outbox.publish(
                EventTopics.INVENTORY_RESERVED,
                orderId.toString(),
                new InventoryReservedPayload(orderId.toString(), expiresAt));
        log.info("Reserved {} SKU(s) for order {} until {}", requested.size(), orderId, expiresAt);
        return ReserveOutcome.RESERVED;
    }

    /** RESERVED | EXPIRING -> COMMITTED: stock leaves the warehouse (on_hand and reserved both drop). */
    @Transactional
    public int commit(UUID orderId) {
        return finish(orderId, ReservationStatus.COMMITTED);
    }

    /** RESERVED | EXPIRING -> RELEASED: the held quantity becomes available again. */
    @Transactional
    public int release(UUID orderId) {
        return finish(orderId, ReservationStatus.RELEASED);
    }

    private int finish(UUID orderId, ReservationStatus target) {
        List<ReservationLine> lines = reservations.lockByOrder(orderId);
        if (lines.isEmpty()) {
            // e.g. order cancelled because the reservation itself failed
            log.info("No reservations for order {}; nothing to mark {}", orderId, target);
            return 0;
        }
        int changed = 0;
        for (ReservationLine line : lines) { // SKU order => stock rows are locked in SKU order too
            if (!line.status().canTransitionTo(target)) {
                continue;
            }
            reservations.transition(orderId, line.sku(), target);
            if (target == ReservationStatus.COMMITTED) {
                // available (= on_hand - reserved) is unchanged, so no stock-level-changed event
                stock.adjust(line.sku(), -line.quantity(), -line.quantity());
            } else {
                stockEvents.availabilityChanged(stock.adjust(line.sku(), 0, -line.quantity()));
            }
            changed++;
        }
        if (changed == 0) {
            log.info("Reservations for order {} already terminal; {} is a no-op", orderId, target);
        } else {
            log.info("Marked {} reservation line(s) of order {} {}", changed, orderId, target);
        }
        return changed;
    }
}
