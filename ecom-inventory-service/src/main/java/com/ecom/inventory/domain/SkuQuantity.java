package com.ecom.inventory.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

public record SkuQuantity(String sku, int quantity) {

    public static final int MAX_SKU_LENGTH = 64;

    public SkuQuantity {
        if (sku == null || sku.isBlank() || sku.length() > MAX_SKU_LENGTH) {
            throw new IllegalArgumentException("Invalid SKU: " + sku);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive for SKU " + sku + ": " + quantity);
        }
    }

    /**
     * Sums duplicate SKUs. The result is sorted by SKU, which is also the lock order for stock rows
     * (every transaction locking several stock rows must lock them in this order to avoid deadlocks).
     */
    public static SortedMap<String, Integer> aggregate(Collection<SkuQuantity> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("At least one line is required");
        }
        SortedMap<String, Integer> totals = new TreeMap<>();
        for (SkuQuantity line : lines) {
            totals.merge(line.sku(), line.quantity(), Math::addExact);
        }
        return Collections.unmodifiableSortedMap(totals);
    }
}
