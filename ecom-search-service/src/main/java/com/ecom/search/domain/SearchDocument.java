package com.ecom.search.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * One document per SKU. Catalog fields and stock fields are written independently (two consumers),
 * each guarded by its own source version. A doc without {@code name} is a stub (stock arrived first)
 * or a tombstone (product/variant deleted) and is never returned by queries.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchDocument(
        String sku,
        String productId,
        String name,
        String description,
        String categoryId,
        String brand,
        String variantName,
        BigDecimal price,
        String currency,
        Boolean active,
        Boolean inStock,
        Integer availableQty,
        Long catalogVersion,
        Boolean catalogDeleted,
        Long stockVersion,
        Long lastUpdated) {}
