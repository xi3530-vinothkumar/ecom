package com.ecom.order.domain;

import com.ecom.common.events.Money;
import java.util.Objects;

/** A cart line priced from the authoritative catalog snapshot at checkout time. */
public record PricedLine(String sku, String name, Money unitPrice, int quantity) {

    public PricedLine {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        if (unitPrice.toBigDecimal().signum() < 0) {
            throw new IllegalArgumentException("unit price must not be negative for " + sku);
        }
    }

    public Money lineTotal() {
        return unitPrice.times(quantity);
    }
}
