package com.ecom.catalog.domain;

import java.util.UUID;

/** Product-level attributes as supplied by an admin write. */
public record ProductDetails(String name, String description, UUID categoryId, String brand, boolean active) {}
