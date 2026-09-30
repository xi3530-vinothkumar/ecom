package com.ecom.order.domain;

import com.ecom.common.events.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money <-> NUMERIC(19,4) column mapping (currency is stored once per order). */
final class MoneyColumns {

    static BigDecimal toColumn(Money money) {
        return money.toBigDecimal().setScale(4, RoundingMode.HALF_EVEN);
    }

    static Money fromColumn(BigDecimal amount, String currency) {
        return Money.of(amount, currency);
    }

    private MoneyColumns() {}
}
