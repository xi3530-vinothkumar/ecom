package com.ecom.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ecom.common.events.Money;
import com.ecom.common.events.payload.OrderCancelledPayload;
import com.ecom.common.events.payload.OrderConfirmedPayload;
import com.ecom.common.events.payload.OrderLinePayload;
import com.ecom.common.events.payload.UserRegisteredPayload;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationEventHandlerTest {

    private final NotificationService service = mock(NotificationService.class);
    private final NotificationEventHandler handler = new NotificationEventHandler(service);
    private final UUID eventId = UUID.randomUUID();
    private final List<OrderLinePayload> lines = List.of(new OrderLinePayload("A", 2), new OrderLinePayload("B", 1));

    @Test
    void orderConfirmedPassesFormattedTotalAndItemCount() {
        handler.onOrderConfirmed(eventId, new OrderConfirmedPayload("o-1", "u-1", lines, Money.of("35.8", "EUR")));
        verify(service).notify(eventId, "u-1", "ORDER_CONFIRMED",
                Map.of("orderId", "o-1", "total", "35.80 EUR", "itemCount", "3"));
    }

    @Test
    void orderCancelledPicksTemplateByReason() {
        handler.onOrderCancelled(eventId, new OrderCancelledPayload("o-1", "u-1", "OUT_OF_STOCK", lines));
        verify(service).notify(eventId, "u-1", "ORDER_CANCELLED_OUT_OF_STOCK", Map.of("orderId", "o-1", "itemCount", "3"));
    }

    @Test
    void userRegisteredSendsWelcome() {
        handler.onUserRegistered(eventId, new UserRegisteredPayload("u-1"));
        verify(service).notify(eventId, "u-1", "WELCOME", Map.of());
    }

    @Test
    void eventWithoutUserIsRejected() {
        assertThatThrownBy(() -> handler.onUserRegistered(eventId, new UserRegisteredPayload(" ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moneyFormatting() {
        assertThat(NotificationEventHandler.formatMoney(Money.of("1234.5", "EUR"))).isEqualTo("1234.50 EUR");
        assertThat(NotificationEventHandler.formatMoney(Money.zero("EUR"))).isEqualTo("0.00 EUR");
    }
}
