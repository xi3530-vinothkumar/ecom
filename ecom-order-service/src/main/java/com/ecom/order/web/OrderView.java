package com.ecom.order.web;

import com.ecom.order.application.OrderQueryService.PaymentAction;
import com.ecom.order.domain.CancelReason;
import com.ecom.order.domain.Order;
import com.ecom.order.domain.OrderStatus;
import com.ecom.order.domain.ShippingAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderView(
        UUID orderId,
        OrderStatus status,
        CancelReason cancelReason,
        List<Line> lines,
        MoneyView subtotal,
        MoneyView vat,
        MoneyView shipping,
        MoneyView total,
        Address shippingAddress,
        PaymentAction paymentAction,
        Instant createdAt,
        Instant updatedAt) {

    public record Line(String sku, String name, MoneyView unitPrice, int quantity, MoneyView lineTotal) {}

    public record Address(String addressId, String line1, String line2, String city, String postalCode, String countryCode) {}

    static OrderView of(Order o, PaymentAction paymentAction) {
        String ccy = o.currency();
        ShippingAddress a = o.shippingAddress();
        return new OrderView(
                o.id(),
                o.status(),
                o.cancelReason(),
                o.lines().stream()
                        .map(l -> new Line(l.sku(), l.name(), MoneyView.of(l.unitPrice(ccy)), l.quantity(),
                                MoneyView.of(l.lineTotal(ccy))))
                        .toList(),
                MoneyView.of(o.subtotal()),
                MoneyView.of(o.vat()),
                MoneyView.of(o.shipping()),
                MoneyView.of(o.total()),
                new Address(a.addressId(), a.line1(), a.line2(), a.city(), a.postalCode(), a.countryCode()),
                paymentAction,
                o.createdAt(),
                o.updatedAt());
    }
}
