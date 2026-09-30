package com.ecom.catalog.domain;

import java.math.BigDecimal;

/** Desired state of one variant in an admin write. */
public record VariantSpec(String sku, String variantName, BigDecimal priceAmount, String priceCurrency, boolean active) {}
