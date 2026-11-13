package com.ecom.inventory.application;

import com.ecom.inventory.config.InventoryProperties;
import com.ecom.inventory.infrastructure.ReservationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class InventoryMetrics {

    public static final String STALE_EXPIRING_GAUGE = "ecom.inventory.reservations.expiring.stale";
    public static final String RESERVATION_FAILURES = "ecom.inventory.reservations.failed";
    public static final String RESERVATIONS_EXPIRED = "ecom.inventory.reservations.expired";

    private final Counter failures;
    private final Counter expired;

    public InventoryMetrics(
            MeterRegistry registry, ReservationRepository reservations, InventoryProperties props, Clock clock) {
        this.failures = Counter.builder(RESERVATION_FAILURES)
                .description("Orders whose stock reservation failed (insufficient or unknown SKU)")
                .register(registry);
        this.expired = Counter.builder(RESERVATIONS_EXPIRED)
                .description("Orders flagged EXPIRING by the reservation sweep")
                .register(registry);
        // Backstop alert (PLAN §3): an EXPIRING reservation older than the threshold means Order never decided.
        Gauge.builder(STALE_EXPIRING_GAUGE, () -> {
                    try {
                        return reservations.countOrdersExpiringSince(
                                clock.instant().minus(props.staleExpiringThreshold()));
                    } catch (RuntimeException _) {
                        return Double.NaN;
                    }
                })
                .description("Orders with a reservation stuck in EXPIRING beyond the threshold")
                .register(registry);
    }

    public void reservationFailed() {
        afterCommit(failures::increment);
    }

    public void reservationExpired() {
        afterCommit(expired::increment);
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
