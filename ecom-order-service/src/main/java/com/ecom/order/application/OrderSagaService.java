package com.ecom.order.application;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.payload.InventoryReservationFailedPayload;
import com.ecom.common.events.payload.InventoryReservedPayload;
import com.ecom.common.events.payload.OrderCancelledPayload;
import com.ecom.common.events.payload.OrderConfirmedPayload;
import com.ecom.common.events.payload.PaymentAuthorizedPayload;
import com.ecom.common.events.payload.PaymentFailedPayload;
import com.ecom.common.events.payload.PaymentVoidedPayload;
import com.ecom.common.events.payload.ReservationExpiredPayload;
import com.ecom.order.domain.CancelReason;
import com.ecom.order.domain.Order;
import com.ecom.order.domain.SagaStep;
import com.ecom.order.infrastructure.OrderRepository;
import com.ecom.order.infrastructure.SagaStateRepository;
import com.ecom.platform.kafka.OutboxWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order is the saga's single decision-maker. Handlers run inside the IdempotentConsumer transaction,
 * so the guarded status update, saga state and any outbox row commit together (or not at all).
 * Correctness does not depend on dedup: a replayed or conflicting event loses the guarded update and
 * emits nothing.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OrderSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);

    private final OrderRepository orders;
    private final SagaStateRepository sagaState;
    private final OutboxWriter outbox;
    private final Clock clock;

    public OrderSagaService(OrderRepository orders, SagaStateRepository sagaState, OutboxWriter outbox, Clock clock) {
        this.orders = orders;
        this.sagaState = sagaState;
        this.outbox = outbox;
        this.clock = clock;
    }

    public void onInventoryReserved(InventoryReservedPayload p) {
        sagaState.record(orderId(p.orderId()), SagaStep.INVENTORY_RESERVED, Instant.now(clock));
    }

    public void onInventoryReservationFailed(InventoryReservationFailedPayload p) {
        UUID id = orderId(p.orderId());
        sagaState.record(id, SagaStep.INVENTORY_RESERVATION_FAILED, Instant.now(clock));
        cancel(id, CancelReason.OUT_OF_STOCK);
    }

    public void onPaymentFailed(PaymentFailedPayload p) {
        UUID id = orderId(p.orderId());
        sagaState.record(id, SagaStep.PAYMENT_FAILED, Instant.now(clock));
        cancel(id, CancelReason.PAYMENT_DECLINED);
    }

    public void onReservationExpired(ReservationExpiredPayload p) {
        UUID id = orderId(p.orderId());
        sagaState.record(id, SagaStep.RESERVATION_EXPIRED, Instant.now(clock));
        // already CONFIRMED: no-op, Inventory commits the reservation from EXPIRING on order-confirmed
        cancel(id, CancelReason.PAYMENT_TIMEOUT);
    }

    public void onPaymentVoided(PaymentVoidedPayload p) {
        sagaState.record(orderId(p.orderId()), SagaStep.PAYMENT_VOIDED, Instant.now(clock));
    }

    public void onPaymentAuthorized(PaymentAuthorizedPayload p) {
        UUID id = orderId(p.orderId());
        Instant now = Instant.now(clock);
        if (orders.confirmIfPending(id, now) == 1) {
            sagaState.record(id, SagaStep.PAYMENT_AUTHORIZED, now);
            Order order = load(id);
            outbox.publish(EventTopics.ORDER_CONFIRMED, id.toString(), new OrderConfirmedPayload(
                    id.toString(), order.userId(), OrderEvents.lines(order), order.total()));
            log.info("Order {} CONFIRMED (transaction {})", id, p.transactionId());
            return;
        }
        orders.findById(id).ifPresentOrElse(order -> {
            switch (order.status()) {
                case CANCELLED -> {
                    // Payment recorded our order-cancelled and voids the authorization itself
                    sagaState.record(id, SagaStep.PAYMENT_AUTHORIZED_LATE, now);
                    log.warn("Late authorization for order {} already CANCELLED ({}), transaction {}; "
                            + "not confirming, Payment voids it", id, order.cancelReason(), p.transactionId());
                }
                case CONFIRMED -> log.debug("Order {} already CONFIRMED; ignoring repeated authorization", id);
                case PENDING -> throw new IllegalStateException("Guarded confirm matched no row for PENDING order " + id);
            }
        }, () -> log.warn("payment-authorized for unknown order {}; ignoring", id));
    }

    private void cancel(UUID id, CancelReason reason) {
        if (orders.cancelIfPending(id, reason, Instant.now(clock)) == 0) {
            log.info("Order {} not PENDING; {} cancellation is a no-op", id, reason);
            return;
        }
        Order order = load(id);
        outbox.publish(EventTopics.ORDER_CANCELLED, id.toString(), new OrderCancelledPayload(
                id.toString(), order.userId(), reason.eventValue(), OrderEvents.lines(order)));
        log.info("Order {} CANCELLED ({})", id, reason);
    }

    private Order load(UUID id) {
        return orders.findWithLines(id).orElseThrow(() -> new IllegalStateException("Order vanished: " + id));
    }

    private static UUID orderId(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Event without orderId");
        }
        return UUID.fromString(raw);
    }
}
