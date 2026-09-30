package com.ecom.cart.domain;

import com.ecom.common.events.Money;

/** Display snapshot of a catalog SKU (never authoritative; checkout re-prices). */
public record Product(String sku, String name, String variantName, Money price, boolean active) {}
