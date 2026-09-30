package com.ecom.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecom.common.events.Money;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderTotalsTest {

    private static final BigDecimal VAT = new BigDecimal("0.21");
    private static final Money SHIPPING = Money.of("4.95", "EUR");

    private static PricedLine line(String sku, String price, int qty) {
        return new PricedLine(sku, sku, Money.of(price, "EUR"), qty);
    }

    private static BigDecimal amount(Money m) {
        return m.toBigDecimal().setScale(2);
    }

    @Test
    void computesSubtotalVatShippingAndTotal() {
        OrderTotals t = OrderTotals.compute(List.of(line("A", "10.00", 2), line("B", "5.50", 1)), VAT, SHIPPING);

        assertThat(amount(t.subtotal())).isEqualByComparingTo("25.50");
        assertThat(amount(t.vat())).isEqualByComparingTo("5.36"); // 5.355 -> half-even -> 5.36
        assertThat(amount(t.shipping())).isEqualByComparingTo("4.95");
        assertThat(amount(t.total())).isEqualByComparingTo("35.81");
        assertThat(t.total().currencyCode()).isEqualTo("EUR");
    }

    @Test
    void vatRoundsHalfEvenToCents() {
        // 0.50 * 0.21 = 0.105 -> 0.10 (even); 1.50 * 0.21 = 0.315 -> 0.32 (even)
        assertThat(amount(OrderTotals.compute(List.of(line("A", "0.50", 1)), VAT, SHIPPING).vat())).isEqualByComparingTo("0.10");
        assertThat(amount(OrderTotals.compute(List.of(line("A", "1.50", 1)), VAT, SHIPPING).vat())).isEqualByComparingTo("0.32");
    }

    @Test
    void vatIsComputedOnSubtotalNotPerLine() {
        // 3 x 0.07: per-line rounding gives 0.0147 -> 0.01 each (0.03); on the subtotal 0.21 * 0.21 = 0.0441 -> 0.04
        OrderTotals t = OrderTotals.compute(
                List.of(line("A", "0.07", 1), line("B", "0.07", 1), line("C", "0.07", 1)), VAT, SHIPPING);
        assertThat(amount(t.vat())).isEqualByComparingTo("0.04");
    }

    @Test
    void lineTotalIsExactForFractionalPrices() {
        PricedLine l = line("A", "19.99", 3);
        assertThat(l.lineTotal().toBigDecimal()).isEqualByComparingTo("59.97");
        assertThat(l.lineTotal().units()).isEqualTo(59);
        assertThat(l.lineTotal().nanos()).isEqualTo(970_000_000);
    }

    @Test
    void zeroRateMeansNoVat() {
        OrderTotals t = OrderTotals.compute(List.of(line("A", "10.00", 1)), BigDecimal.ZERO, SHIPPING);
        assertThat(amount(t.vat())).isEqualByComparingTo("0.00");
        assertThat(amount(t.total())).isEqualByComparingTo("14.95");
    }

    @Test
    void rejectsEmptyOrderAndMixedCurrencies() {
        assertThatThrownBy(() -> OrderTotals.compute(List.of(), VAT, SHIPPING)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderTotals.compute(
                        List.of(new PricedLine("A", "A", Money.of("1.00", "USD"), 1)), VAT, SHIPPING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    void pricedLineRejectsNonPositiveQuantityAndNegativePrice() {
        assertThatThrownBy(() -> line("A", "1.00", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line("A", "-1.00", 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
