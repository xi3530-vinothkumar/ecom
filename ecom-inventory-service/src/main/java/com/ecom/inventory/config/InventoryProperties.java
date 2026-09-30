package com.ecom.inventory.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param reservationTtl how long a reservation holds stock before the sweep flags it EXPIRING
 * @param reservationSweepInterval fixed delay between sweep runs
 * @param sweepBatchSize max orders flagged per sweep transaction
 * @param sweepEnabled switch for the scheduled sweep (tests drive it manually)
 * @param staleExpiringThreshold EXPIRING reservations older than this are reported for alerting
 */
@ConfigurationProperties("ecom.inventory")
public record InventoryProperties(
        @DefaultValue("15m") Duration reservationTtl,
        @DefaultValue("60s") Duration reservationSweepInterval,
        @DefaultValue("200") int sweepBatchSize,
        @DefaultValue("true") boolean sweepEnabled,
        @DefaultValue("1h") Duration staleExpiringThreshold) {}
