package com.ecom.order.application;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param pendingStaleAfter PENDING orders older than this are counted by the {@code ecom.order.pending.stale} gauge
 */
@ConfigurationProperties("ecom.order")
public record OrderProperties(
        @DefaultValue("EUR") String currency,
        @DefaultValue("0.21") BigDecimal vatRate,
        @DefaultValue("4.95") BigDecimal shippingFee,
        @DefaultValue("30m") Duration pendingStaleAfter,
        @DefaultValue("60s") Duration pendingStaleRefresh) {}
