package com.ecom.common.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void roundTripsBigDecimal() {
        Money m = Money.of("19.99", "EUR");
        assertThat(m.units()).isEqualTo(19);
        assertThat(m.nanos()).isEqualTo(990_000_000);
        assertThat(m.toBigDecimal()).isEqualByComparingTo("19.99");
        assertThat(m.toMinorUnits()).isEqualTo(1999);
    }

    @Test
    void negativeAmountsKeepSameSign() {
        Money m = Money.of("-1.50", "EUR");
        assertThat(m.units()).isEqualTo(-1);
        assertThat(m.nanos()).isEqualTo(-500_000_000);
    }

    @Test
    void arithmetic() {
        Money total = Money.of("19.99", "EUR").times(3).plus(Money.of("4.95", "EUR"));
        assertThat(total.toBigDecimal()).isEqualByComparingTo("64.92");
        assertThat(Money.of("10.00", "EUR").timesRate(new BigDecimal("0.21")).toBigDecimal())
                .isEqualByComparingTo("2.10");
    }

    @Test
    void rejectsCurrencyMismatchAndBadInput() {
        assertThatThrownBy(() -> Money.of("1", "EUR").plus(Money.of("1", "USD")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money("EUR", 1, -5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money("EURO", 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
