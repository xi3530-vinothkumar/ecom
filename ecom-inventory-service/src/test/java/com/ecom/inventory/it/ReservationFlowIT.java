package com.ecom.inventory.it;

import static com.ecom.inventory.domain.ReservationStatus.COMMITTED;
import static com.ecom.inventory.domain.ReservationStatus.EXPIRING;
import static com.ecom.inventory.domain.ReservationStatus.RELEASED;
import static com.ecom.inventory.domain.ReservationStatus.RESERVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.Money;
import com.ecom.common.events.payload.InventoryReservationFailedPayload;
import com.ecom.common.events.payload.InventoryReservedPayload;
import com.ecom.common.events.payload.OrderCancelledPayload;
import com.ecom.common.events.payload.OrderConfirmedPayload;
import com.ecom.common.events.payload.OrderCreatedPayload;
import com.ecom.common.events.payload.OrderLinePayload;
import com.ecom.common.events.payload.StockLevelChangedPayload;
import com.ecom.inventory.application.InventoryMetrics;
import com.ecom.inventory.application.ReservationSweeper;
import com.ecom.inventory.infrastructure.OrderEventsListener;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationFlowIT extends AbstractInventoryIT {

    @Autowired OrderEventsListener listener;
    @Autowired ReservationSweeper sweeper;
    @Autowired MeterRegistry meters;

    private void create(UUID orderId, List<OrderLinePayload> lines) {
        listener.onOrderCreated(record(EventTopics.ORDER_CREATED, orderId.toString(),
                new OrderCreatedPayload(orderId.toString(), "user-1", lines)));
    }

    private void confirm(UUID orderId, List<OrderLinePayload> lines) {
        listener.onOrderConfirmed(record(EventTopics.ORDER_CONFIRMED, orderId.toString(),
                new OrderConfirmedPayload(orderId.toString(), "user-1", lines, Money.of("10.00", "EUR"))));
    }

    private void cancel(UUID orderId, List<OrderLinePayload> lines) {
        listener.onOrderCancelled(record(EventTopics.ORDER_CANCELLED, orderId.toString(),
                new OrderCancelledPayload(orderId.toString(), "user-1", OrderCancelledPayload.PAYMENT_DECLINED, lines)));
    }

    private List<StockLevelChangedPayload> stockEvents(String sku) {
        return outboxEvents(EventTopics.STOCK_LEVEL_CHANGED, sku).stream()
                .map(e -> e.payloadAs(mapper, StockLevelChangedPayload.class))
                .toList();
    }

    @Test
    void reservesAllLinesAggregatingDuplicateSkus() {
        String a = sku("A"), b = sku("B");
        stock(a, 10);
        stock(b, 5);
        UUID orderId = UUID.randomUUID();

        create(orderId, lines(a, 2, b, 5, a, 1));

        assertThat(level(a).reserved()).isEqualTo(3);
        assertThat(level(a).onHand()).isEqualTo(10);
        assertThat(level(b).reserved()).isEqualTo(5);
        assertThat(reservationStatuses(orderId)).containsExactlyInAnyOrderEntriesOf(Map.of(a, RESERVED, b, RESERVED));
        assertThat(jdbc.queryForObject("SELECT qty FROM stock_reservations WHERE order_id = ? AND sku = ?",
                Integer.class, orderId, a)).isEqualTo(3);

        var reserved = outboxEvents(EventTopics.INVENTORY_RESERVED, orderId.toString());
        assertThat(reserved).hasSize(1);
        Instant expiresAt = reserved.getFirst().payloadAs(mapper, InventoryReservedPayload.class).expiresAt();
        assertThat(expiresAt).isCloseTo(Instant.now().plus(Duration.ofMinutes(15)), within(1, ChronoUnit.MINUTES));

        StockLevelChangedPayload aEvent = stockEvents(a).getLast();
        assertThat(aEvent.availableQty()).isEqualTo(7);
        assertThat(aEvent.inStock()).isTrue();
        assertThat(aEvent.version()).isEqualTo(level(a).version());
        StockLevelChangedPayload bEvent = stockEvents(b).getLast();
        assertThat(bEvent.availableQty()).isZero();
        assertThat(bEvent.inStock()).isFalse();
    }

    @Test
    void shortLineReservesNothingAndReportsShortSkus() {
        String a = sku("A"), b = sku("B"), unknown = sku("UNKNOWN");
        stock(a, 10);
        stock(b, 1);
        UUID orderId = UUID.randomUUID();
        double failuresBefore = meters.counter(InventoryMetrics.RESERVATION_FAILURES).count();

        create(orderId, lines(a, 2, b, 2, unknown, 1));

        assertThat(level(a).reserved()).isZero();
        assertThat(level(b).reserved()).isZero();
        assertThat(reservationStatuses(orderId)).isEmpty();
        assertThat(outboxEvents(EventTopics.INVENTORY_RESERVED, orderId.toString())).isEmpty();
        assertThat(stockEvents(a)).isEmpty();
        var failed = outboxEvents(EventTopics.INVENTORY_RESERVATION_FAILED, orderId.toString());
        assertThat(failed).hasSize(1);
        assertThat(failed.getFirst().payloadAs(mapper, InventoryReservationFailedPayload.class).shortSkus())
                .containsExactlyInAnyOrder(b, unknown);
        assertThat(meters.counter(InventoryMetrics.RESERVATION_FAILURES).count()).isEqualTo(failuresBefore + 1);
    }

    @Test
    void duplicateDeliveryDoesNotDoubleReserve() {
        String a = sku("A");
        stock(a, 10);
        UUID orderId = UUID.randomUUID();
        ConsumerRecord<String, String> rec = record(EventTopics.ORDER_CREATED, orderId.toString(),
                new OrderCreatedPayload(orderId.toString(), "user-1", lines(a, 4)));

        listener.onOrderCreated(rec); // same eventId twice -> dedup store
        listener.onOrderCreated(rec);
        create(orderId, lines(a, 4)); // same order, new eventId -> guarded by existing reservation rows

        assertThat(level(a).reserved()).isEqualTo(4);
        assertThat(outboxEvents(EventTopics.INVENTORY_RESERVED, orderId.toString())).hasSize(1);
        assertThat(stockEvents(a)).hasSize(1);
    }

    @Test
    void confirmCommitsReservationAndIsIdempotent() {
        String a = sku("A"), b = sku("B");
        stock(a, 10);
        stock(b, 3);
        UUID orderId = UUID.randomUUID();
        create(orderId, lines(a, 2, b, 3));
        long eventsBefore = stockEvents(a).size();

        confirm(orderId, lines(a, 2, b, 3));
        confirm(orderId, lines(a, 2, b, 3));
        cancel(orderId, lines(a, 2, b, 3)); // terminal: late cancel must not release committed stock

        assertThat(level(a).onHand()).isEqualTo(8);
        assertThat(level(a).reserved()).isZero();
        assertThat(level(b).onHand()).isZero();
        assertThat(level(b).reserved()).isZero();
        assertThat(reservationStatuses(orderId)).containsExactlyInAnyOrderEntriesOf(Map.of(a, COMMITTED, b, COMMITTED));
        // commit leaves available unchanged => no stock event
        assertThat(stockEvents(a)).hasSize((int) eventsBefore);
    }

    @Test
    void cancelReleasesReservationAndIsIdempotent() {
        String a = sku("A");
        stock(a, 10);
        UUID orderId = UUID.randomUUID();
        create(orderId, lines(a, 6));

        cancel(orderId, lines(a, 6));
        cancel(orderId, lines(a, 6));
        confirm(orderId, lines(a, 6)); // terminal: late confirm must not commit released stock

        assertThat(level(a).onHand()).isEqualTo(10);
        assertThat(level(a).reserved()).isZero();
        assertThat(reservationStatuses(orderId)).containsEntry(a, RELEASED);
        List<StockLevelChangedPayload> events = stockEvents(a);
        assertThat(events).extracting(StockLevelChangedPayload::availableQty).containsExactly(4, 10);
        assertThat(events.get(1).version()).isGreaterThan(events.get(0).version());
    }

    @Test
    void cancelWithoutReservationIsNoOp() {
        UUID orderId = UUID.randomUUID();
        cancel(orderId, lines(sku("A"), 1));
        assertThat(reservationStatuses(orderId)).isEmpty();
    }

    @Test
    void sweepFlagsExpiringOnceAndKeepsStockReserved() {
        String a = sku("A"), b = sku("B");
        stock(a, 10);
        stock(b, 10);
        UUID orderId = UUID.randomUUID();
        create(orderId, lines(a, 2, b, 1));
        jdbc.update("UPDATE stock_reservations SET expires_at = now() - interval '1 minute' WHERE order_id = ?", orderId);

        assertThat(sweeper.sweep()).isGreaterThanOrEqualTo(1);
        sweeper.sweep();

        assertThat(reservationStatuses(orderId)).containsExactlyInAnyOrderEntriesOf(Map.of(a, EXPIRING, b, EXPIRING));
        assertThat(level(a).reserved()).isEqualTo(2);
        assertThat(level(b).reserved()).isEqualTo(1);
        assertThat(outboxEvents(EventTopics.RESERVATION_EXPIRED, orderId.toString())).hasSize(1);

        // Order confirmed after all (slow 3DS): commit from EXPIRING
        confirm(orderId, lines(a, 2, b, 1));
        assertThat(reservationStatuses(orderId)).containsExactlyInAnyOrderEntriesOf(Map.of(a, COMMITTED, b, COMMITTED));
        assertThat(level(a).onHand()).isEqualTo(8);
        assertThat(level(a).reserved()).isZero();
    }

    @Test
    void cancelReleasesFromExpiring() {
        String a = sku("A");
        stock(a, 5);
        UUID orderId = UUID.randomUUID();
        create(orderId, lines(a, 5));
        jdbc.update("UPDATE stock_reservations SET expires_at = now() - interval '1 minute' WHERE order_id = ?", orderId);
        sweeper.sweep();

        cancel(orderId, lines(a, 5));

        assertThat(reservationStatuses(orderId)).containsEntry(a, RELEASED);
        assertThat(level(a).reserved()).isZero();
        assertThat(level(a).onHand()).isEqualTo(5);
    }

    @Test
    void sweepIgnoresReservationsNotYetDue() {
        String a = sku("A");
        stock(a, 5);
        UUID orderId = UUID.randomUUID();
        create(orderId, lines(a, 1));

        sweeper.sweep();

        assertThat(reservationStatuses(orderId)).containsEntry(a, RESERVED);
        assertThat(outboxEvents(EventTopics.RESERVATION_EXPIRED, orderId.toString())).isEmpty();
    }

    @Test
    void staleExpiringGaugeCountsOrdersStuckForOverAnHour() {
        String a = sku("A");
        stock(a, 5);
        UUID orderId = UUID.randomUUID();
        create(orderId, lines(a, 1));
        double before = meters.get(InventoryMetrics.STALE_EXPIRING_GAUGE).gauge().value();

        jdbc.update("UPDATE stock_reservations SET status = 'EXPIRING', updated_at = now() - interval '2 hours'"
                + " WHERE order_id = ?", orderId);

        assertThat(meters.get(InventoryMetrics.STALE_EXPIRING_GAUGE).gauge().value()).isEqualTo(before + 1);
    }
}
