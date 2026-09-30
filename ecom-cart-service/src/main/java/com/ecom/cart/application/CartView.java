package com.ecom.cart.application;

import com.ecom.common.events.Money;
import java.util.List;

/**
 * Hydrated cart for display. Prices are indicative only (L1-cached); checkout re-prices.
 *
 * @param catalogReachable false when Catalog could not be queried: lines are then returned un-hydrated
 */
public record CartView(List<Line> lines, Money subtotal, int itemCount, boolean catalogReachable) {

    /** {@code available} is false for unknown or inactive SKUs; those lines do not count toward the subtotal. */
    public record Line(
            String sku, String name, String variantName, int quantity, Money unitPrice, Money lineTotal,
            boolean available) {}
}
