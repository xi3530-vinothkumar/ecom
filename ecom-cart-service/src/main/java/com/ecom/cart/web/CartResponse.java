package com.ecom.cart.web;

import com.ecom.cart.application.CartView;
import com.ecom.common.events.Money;
import java.util.List;

public record CartResponse(List<Line> lines, Price subtotal, int itemCount, boolean catalogReachable) {

    public record Price(String amount, String currency) {
        static Price of(Money m) {
            return m == null ? null : new Price(m.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString(), m.currencyCode());
        }
    }

    public record Line(
            String sku, String name, String variantName, int quantity, Price unitPrice, Price lineTotal,
            boolean available) {}

    static CartResponse of(CartView v) {
        return new CartResponse(
                v.lines().stream()
                        .map(l -> new Line(l.sku(), l.name(), l.variantName(), l.quantity(),
                                Price.of(l.unitPrice()), Price.of(l.lineTotal()), l.available()))
                        .toList(),
                Price.of(v.subtotal()),
                v.itemCount(),
                v.catalogReachable());
    }
}
