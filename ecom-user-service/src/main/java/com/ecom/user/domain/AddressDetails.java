package com.ecom.user.domain;

import java.util.Locale;
import java.util.Objects;

/** Validated, normalized address fields (country code upper-cased, blank line2 -> null). */
public record AddressDetails(String line1, String line2, String city, String postalCode, String countryCode) {

    public AddressDetails {
        line1 = Objects.requireNonNull(line1).trim();
        line2 = line2 == null || line2.isBlank() ? null : line2.trim();
        city = Objects.requireNonNull(city).trim();
        postalCode = Objects.requireNonNull(postalCode).trim();
        countryCode = Objects.requireNonNull(countryCode).trim().toUpperCase(Locale.ROOT);
    }
}
