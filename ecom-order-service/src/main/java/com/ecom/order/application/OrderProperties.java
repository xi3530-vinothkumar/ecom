package com.ecom.order.application;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ecom.order")
public class OrderProperties {

    private String currency = "EUR";
    private BigDecimal vatRate = new BigDecimal("0.21");
    private BigDecimal shippingFee = new BigDecimal("4.95");
    /** PENDING orders older than this are counted by the {@code ecom.order.pending.stale} gauge. */
    private Duration pendingStaleAfter = Duration.ofMinutes(30);
    private Duration pendingStaleRefresh = Duration.ofSeconds(60);

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getVatRate() {
        return vatRate;
    }

    public void setVatRate(BigDecimal vatRate) {
        this.vatRate = vatRate;
    }

    public BigDecimal getShippingFee() {
        return shippingFee;
    }

    public void setShippingFee(BigDecimal shippingFee) {
        this.shippingFee = shippingFee;
    }

    public Duration getPendingStaleAfter() {
        return pendingStaleAfter;
    }

    public void setPendingStaleAfter(Duration pendingStaleAfter) {
        this.pendingStaleAfter = pendingStaleAfter;
    }

    public Duration getPendingStaleRefresh() {
        return pendingStaleRefresh;
    }

    public void setPendingStaleRefresh(Duration pendingStaleRefresh) {
        this.pendingStaleRefresh = pendingStaleRefresh;
    }
}
