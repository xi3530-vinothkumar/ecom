package com.ecom.order.web;

import com.ecom.common.events.Money;
import java.math.RoundingMode;

/** Client-facing amount as a decimal string (never a JSON float). */
public record MoneyView(String amount, String currency) {

    static MoneyView of(Money money) {
        return new MoneyView(money.toBigDecimal().setScale(2, RoundingMode.HALF_EVEN).toPlainString(), money.currencyCode());
    }
}
