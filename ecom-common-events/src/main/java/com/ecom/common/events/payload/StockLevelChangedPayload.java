package com.ecom.common.events.payload;

/** version = stock row version; Search applies the update only if version > stored stockVersion. */
public record StockLevelChangedPayload(String sku, int availableQty, boolean inStock, long version) implements EventPayload {}
