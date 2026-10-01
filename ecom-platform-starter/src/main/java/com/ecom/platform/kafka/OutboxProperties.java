package com.ecom.platform.kafka;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param processedEventsRetention must exceed Kafka retention + DLT replay window
 */
@ConfigurationProperties("ecom.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean relayEnabled,
        @DefaultValue("100") int batchSize,
        @DefaultValue("10s") Duration sendTimeout,
        @DefaultValue("7d") Duration publishedRetention,
        @DefaultValue("14d") Duration processedEventsRetention) {}
