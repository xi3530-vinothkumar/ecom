package com.ecom.platform.kafka;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ecom.outbox")
public class OutboxProperties {

    private boolean relayEnabled = true;
    private int batchSize = 100;
    private Duration sendTimeout = Duration.ofSeconds(10);
    private Duration publishedRetention = Duration.ofDays(7);
    /** Must exceed Kafka retention + DLT replay window. */
    private Duration processedEventsRetention = Duration.ofDays(14);

    public boolean isRelayEnabled() {
        return relayEnabled;
    }

    public void setRelayEnabled(boolean relayEnabled) {
        this.relayEnabled = relayEnabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    public Duration getPublishedRetention() {
        return publishedRetention;
    }

    public void setPublishedRetention(Duration publishedRetention) {
        this.publishedRetention = publishedRetention;
    }

    public Duration getProcessedEventsRetention() {
        return processedEventsRetention;
    }

    public void setProcessedEventsRetention(Duration processedEventsRetention) {
        this.processedEventsRetention = processedEventsRetention;
    }
}
