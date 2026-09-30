package com.ecom.cart.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param guestTtl guest cart lifetime, refreshed on every write (user carts never expire)
 * @param maxQuantity per-line quantity cap
 * @param maxLines max distinct SKUs per cart
 * @param processedEventTtl retention of Kafka dedup markers
 */
@ConfigurationProperties("ecom.cart")
public record CartProperties(
        @DefaultValue("30d") Duration guestTtl,
        @DefaultValue("99") int maxQuantity,
        @DefaultValue("100") int maxLines,
        @DefaultValue("7d") Duration processedEventTtl,
        @DefaultValue CatalogCache catalogCache) {

    public record CatalogCache(@DefaultValue("30s") Duration ttl, @DefaultValue("10000") long maxSize) {}
}
