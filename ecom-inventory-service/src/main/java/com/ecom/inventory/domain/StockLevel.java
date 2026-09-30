package com.ecom.inventory.domain;

import java.time.Instant;

public record StockLevel(String sku, String warehouseId, int onHand, int reserved, long version, Instant updatedAt) {

    public static final String DEFAULT_WAREHOUSE = "DEFAULT";

    public int available() {
        return onHand - reserved;
    }

    public boolean inStock() {
        return available() > 0;
    }

    public boolean canReserve(int quantity) {
        return available() >= quantity;
    }
}
