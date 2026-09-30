package com.ecom.common.events;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Monetary amount in the google.type.Money shape: {@code units} whole units plus {@code nanos}
 * (10^-9 units, same sign as units). Used in event payloads and mapped 1:1 to the proto Money.
 * Never use double/float for money.
 */
public record Money(String currencyCode, long units, int nanos) {

    private static final BigDecimal NANOS_PER_UNIT = BigDecimal.valueOf(1_000_000_000L);

    public Money {
        Objects.requireNonNull(currencyCode, "currencyCode");
        if (currencyCode.length() != 3) {
            throw new IllegalArgumentException("currencyCode must be ISO 4217: " + currencyCode);
        }
        if (nanos <= -1_000_000_000 || nanos >= 1_000_000_000) {
            throw new IllegalArgumentException("nanos out of range: " + nanos);
        }
        if ((units > 0 && nanos < 0) || (units < 0 && nanos > 0)) {
            throw new IllegalArgumentException("units and nanos must have the same sign");
        }
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        BigDecimal scaled = amount.setScale(9, RoundingMode.HALF_EVEN);
        long units = scaled.longValue();
        int nanos = scaled.subtract(BigDecimal.valueOf(units)).multiply(NANOS_PER_UNIT).intValueExact();
        return new Money(currencyCode, units, nanos);
    }

    public static Money of(String amount, String currencyCode) {
        return of(new BigDecimal(amount), currencyCode);
    }

    public static Money zero(String currencyCode) {
        return new Money(currencyCode, 0, 0);
    }

    public BigDecimal toBigDecimal() {
        return BigDecimal.valueOf(units)
                .add(BigDecimal.valueOf(nanos).divide(NANOS_PER_UNIT))
                .stripTrailingZeros();
    }

    /** Amount in minor units (e.g. cents for EUR), as PSP APIs expect. Rounds half-even to 2 decimals. */
    public long toMinorUnits() {
        return toBigDecimal().setScale(2, RoundingMode.HALF_EVEN).movePointRight(2).longValueExact();
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return of(toBigDecimal().add(other.toBigDecimal()), currencyCode);
    }

    public Money times(long quantity) {
        return of(toBigDecimal().multiply(BigDecimal.valueOf(quantity)), currencyCode);
    }

    public Money timesRate(BigDecimal rate) {
        return of(toBigDecimal().multiply(rate).setScale(2, RoundingMode.HALF_EVEN), currencyCode);
    }

    private void requireSameCurrency(Money other) {
        if (!currencyCode.equals(other.currencyCode)) {
            throw new IllegalArgumentException("Currency mismatch: " + currencyCode + " vs " + other.currencyCode);
        }
    }
}
