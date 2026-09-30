package com.ecom.order.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fingerprint of the checkout body stored next to the Idempotency-Key, so a replay with the same key
 * but a different body is detected (422) instead of silently returning the first order.
 */
public final class CheckoutRequestHash {

    public static String of(String paymentMethodRef, String shippingAddressId) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            // length-prefixed fields: ("ab","c") and ("a","bc") must not collide
            String canonical = paymentMethodRef.length() + ":" + paymentMethodRef + "|"
                    + shippingAddressId.length() + ":" + shippingAddressId;
            return HexFormat.of().formatHex(sha.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private CheckoutRequestHash() {}
}
