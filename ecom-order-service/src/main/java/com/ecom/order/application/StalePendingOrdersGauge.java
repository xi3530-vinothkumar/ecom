package com.ecom.order.application;

import com.ecom.order.domain.OrderStatus;
import com.ecom.order.infrastructure.OrderRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code ecom.order.pending.stale}: PENDING orders older than {@code ecom.order.pending-stale-after}.
 * Non-zero means a saga event was lost or dead-lettered; alerting pages a human (no auto-release).
 * Refreshed on a schedule so Prometheus scrapes never hit the database.
 */
@Component
public class StalePendingOrdersGauge {

    private static final Logger log = LoggerFactory.getLogger(StalePendingOrdersGauge.class);

    private final OrderRepository orders;
    private final OrderProperties props;
    private final Clock clock;
    private final AtomicLong value = new AtomicLong();

    public StalePendingOrdersGauge(OrderRepository orders, OrderProperties props, Clock clock, MeterRegistry meters) {
        this.orders = orders;
        this.props = props;
        this.clock = clock;
        Gauge.builder("ecom.order.pending.stale", value, AtomicLong::get)
                .description("PENDING orders older than the stale threshold")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${ecom.order.pending-stale-refresh:60s}", initialDelayString = "5s")
    public void refresh() {
        try {
            Instant cutoff = Instant.now(clock).minus(props.pendingStaleAfter());
            value.set(orders.countByStatusAndCreatedAtBefore(OrderStatus.PENDING, cutoff));
        } catch (RuntimeException e) {
            log.warn("Could not refresh ecom.order.pending.stale: {}", e.toString());
        }
    }

    public long current() {
        return value.get();
    }
}
