package com.ecom.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.Money;
import com.ecom.common.events.payload.InventoryReservationFailedPayload;
import com.ecom.common.events.payload.OrderCancelledPayload;
import com.ecom.common.events.payload.OrderConfirmedPayload;
import com.ecom.common.events.payload.OrderLinePayload;
import com.ecom.common.events.payload.PaymentAuthorizedPayload;
import com.ecom.common.events.payload.ReservationExpiredPayload;
import com.ecom.order.domain.CancelReason;
import com.ecom.order.domain.Order;
import com.ecom.order.domain.OrderTotals;
import com.ecom.order.domain.PricedLine;
import com.ecom.order.domain.SagaStep;
import com.ecom.order.domain.ShippingAddress;
import com.ecom.order.infrastructure.OrderRepository;
import com.ecom.order.infrastructure.SagaStateRepository;
import com.ecom.platform.kafka.OutboxWriter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderSagaServiceTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final SagaStateRepository saga = mock(SagaStateRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC);
    private final OrderSagaService service = new OrderSagaService(orders, saga, outbox, clock);

    private Order order;

    @BeforeEach
    void setUp() {
        List<PricedLine> lines = List.of(new PricedLine("SKU-1", "Thing", Money.of("10.00", "EUR"), 2));
        order = Order.placePending("user-1", "key", "hash", "pm_card_visa",
                new ShippingAddress("a1", "Street 1", null, "Amsterdam", "1000AA", "NL"),
                lines, OrderTotals.compute(lines, new BigDecimal("0.21"), Money.of("4.95", "EUR")), clock.instant());
    }

    @Test
    void authorizationConfirmsPendingOrderAndEmitsOrderConfirmed() {
        when(orders.confirmIfPending(order.id(), clock.instant())).thenReturn(1);
        when(orders.findWithLines(order.id())).thenReturn(Optional.of(order));

        service.onPaymentAuthorized(new PaymentAuthorizedPayload(order.id().toString(), "tx-1", order.total()));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(eq(EventTopics.ORDER_CONFIRMED), eq(order.id().toString()), payload.capture());
        OrderConfirmedPayload confirmed = (OrderConfirmedPayload) payload.getValue();
        assertThat(confirmed.userId()).isEqualTo("user-1");
        assertThat(confirmed.lines()).containsExactly(new OrderLinePayload("SKU-1", 2));
        assertThat(confirmed.total().toBigDecimal()).isEqualByComparingTo("29.15");
        verify(saga).record(order.id(), SagaStep.PAYMENT_AUTHORIZED, clock.instant());
    }

    @Test
    void lateAuthorizationOnCancelledOrderEmitsNothing() {
        when(orders.confirmIfPending(any(), any())).thenReturn(0);
        Order cancelled = mock(Order.class);
        when(cancelled.status()).thenReturn(com.ecom.order.domain.OrderStatus.CANCELLED);
        when(orders.findById(order.id())).thenReturn(Optional.of(cancelled));

        service.onPaymentAuthorized(new PaymentAuthorizedPayload(order.id().toString(), "tx-1", order.total()));

        verify(outbox, never()).publish(anyString(), anyString(), any());
        verify(saga).record(order.id(), SagaStep.PAYMENT_AUTHORIZED_LATE, clock.instant());
    }

    @Test
    void reservationFailureCancelsWithLinesInPayload() {
        when(orders.cancelIfPending(order.id(), CancelReason.OUT_OF_STOCK, clock.instant())).thenReturn(1);
        when(orders.findWithLines(order.id())).thenReturn(Optional.of(order));

        service.onInventoryReservationFailed(new InventoryReservationFailedPayload(order.id().toString(), List.of("SKU-1")));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(eq(EventTopics.ORDER_CANCELLED), eq(order.id().toString()), payload.capture());
        OrderCancelledPayload cancelled = (OrderCancelledPayload) payload.getValue();
        assertThat(cancelled.reason()).isEqualTo(OrderCancelledPayload.OUT_OF_STOCK);
        assertThat(cancelled.lines()).containsExactly(new OrderLinePayload("SKU-1", 2));
    }

    @Test
    void expiryAfterConfirmationIsNoOp() {
        when(orders.cancelIfPending(any(), any(), any())).thenReturn(0);

        service.onReservationExpired(new ReservationExpiredPayload(UUID.randomUUID().toString()));

        verify(outbox, never()).publish(anyString(), anyString(), any());
    }

    @Test
    void malformedOrderIdIsRejectedAsNonRetryable() {
        assertThatThrownBy(() -> service.onReservationExpired(new ReservationExpiredPayload("not-a-uuid")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
