package com.ecom.common.events.payload;

import com.ecom.common.events.Money;
import java.util.List;

/**
 * Full product state (event-carried state transfer), used for product-created and product-updated.
 * version = product row version; Search applies the update only if version > stored catalogVersion.
 */
public record ProductChangedPayload(
        String productId,
        String name,
        String description,
        String categoryId,
        String brand,
        boolean active,
        long version,
        List<Variant> variants) implements EventPayload {

    public record Variant(String sku, String variantName, Money price) {}
}
