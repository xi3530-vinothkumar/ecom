package com.ecom.notification.application;

import com.ecom.common.events.Money;
import com.ecom.common.events.payload.OrderCancelledPayload;
import com.ecom.common.events.payload.OrderConfirmedPayload;
import com.ecom.common.events.payload.OrderLinePayload;
import com.ecom.common.events.payload.UserRegisteredPayload;
import com.ecom.notification.domain.TemplateKeys;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Maps each event to a template key and the event-derived (non-PII) template variables. */
@Component
public class NotificationEventHandler {

    private final NotificationService notifications;

    public NotificationEventHandler(NotificationService notifications) {
        this.notifications = notifications;
    }

    public void onUserRegistered(UUID eventId, UserRegisteredPayload p) {
        notifications.notify(eventId, requireUser(p.userId()), TemplateKeys.WELCOME, Map.of());
    }

    public void onOrderConfirmed(UUID eventId, OrderConfirmedPayload p) {
        Objects.requireNonNull(p.total(), "order-confirmed without total");
        notifications.notify(eventId, requireUser(p.userId()), TemplateKeys.ORDER_CONFIRMED, Map.of(
                "orderId", p.orderId(),
                "total", formatMoney(p.total()),
                "itemCount", Integer.toString(itemCount(p.lines()))));
    }

    public void onOrderCancelled(UUID eventId, OrderCancelledPayload p) {
        notifications.notify(eventId, requireUser(p.userId()), TemplateKeys.orderCancelled(p.reason()), Map.of(
                "orderId", p.orderId(),
                "itemCount", Integer.toString(itemCount(p.lines()))));
    }

    static String formatMoney(Money m) {
        return m.toBigDecimal().setScale(2, RoundingMode.HALF_EVEN).toPlainString() + " " + m.currencyCode();
    }

    private static int itemCount(List<OrderLinePayload> lines) {
        return lines == null ? 0 : lines.stream().mapToInt(OrderLinePayload::quantity).sum();
    }

    private static String requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Event without userId");
        }
        return userId;
    }
}
