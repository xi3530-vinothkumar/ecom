package com.ecom.order.domain;

import com.ecom.common.events.Money;
import java.math.BigDecimal;
import java.util.List;

/**
 * subtotal = sum(unit price x qty); VAT = subtotal x rate rounded half-even to cents; flat shipping
 * (not VAT-able in the MVP); total = subtotal + VAT + shipping.
 */
public record OrderTotals(Money subtotal, Money vat, Money shipping, Money total) {

    public static OrderTotals compute(List<PricedLine> lines, BigDecimal vatRate, Money shippingFee) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("an order needs at least one line");
        }
        if (vatRate.signum() < 0) {
            throw new IllegalArgumentException("VAT rate must not be negative: " + vatRate);
        }
        String currency = shippingFee.currencyCode();
        Money subtotal = Money.zero(currency);
        for (PricedLine line : lines) {
            subtotal = subtotal.plus(line.lineTotal());
        }
        Money vat = subtotal.timesRate(vatRate);
        Money total = subtotal.plus(vat).plus(shippingFee);
        return new OrderTotals(subtotal, vat, shippingFee, total);
    }
}
