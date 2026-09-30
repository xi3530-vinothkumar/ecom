package com.ecom.payment.infrastructure.psp;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stripe webhook signature scheme: {@code Stripe-Signature: t=<unix>,v1=<hex>[,v1=<hex>...]} where
 * {@code v1 = HMAC-SHA256(secret, t + "." + rawBody)}. Several v1 entries appear during secret
 * rotation; any match is accepted. Must run on the raw bytes, before any JSON parsing.
 */
public class StripeSignatureVerifier {

    public static final String HEADER = "Stripe-Signature";
    private static final String HMAC = "HmacSHA256";

    private final byte[] secret;
    private final Duration tolerance;
    private final Clock clock;

    public StripeSignatureVerifier(String secret, Duration tolerance, Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Webhook secret must be configured");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tolerance = Objects.requireNonNull(tolerance, "tolerance");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @throws InvalidSignatureException if the header is missing/malformed, stale, or no v1 matches */
    public void verify(byte[] payload, String header) {
        if (header == null || header.isBlank()) {
            throw new InvalidSignatureException("Missing " + HEADER + " header");
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>(2);
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (key.equals("t")) {
                try {
                    timestamp = Long.parseLong(value);
                } catch (NumberFormatException _) {
                    throw new InvalidSignatureException("Malformed timestamp");
                }
            } else if (key.equals("v1")) {
                signatures.add(value);
            }
        }
        if (timestamp == null) {
            throw new InvalidSignatureException("No timestamp in signature header");
        }
        if (signatures.isEmpty()) {
            throw new InvalidSignatureException("No v1 signature in signature header");
        }
        long age = clock.instant().getEpochSecond() - timestamp;
        if (Math.abs(age) > tolerance.toSeconds()) {
            throw new InvalidSignatureException("Signature timestamp outside tolerance");
        }
        byte[] expected = hexHmac(timestamp, payload);
        boolean matched = false;
        for (String candidate : signatures) {
            // constant-time: never short-circuit on the first differing byte
            matched |= MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.US_ASCII));
        }
        if (!matched) {
            throw new InvalidSignatureException("Signature mismatch");
        }
    }

    /** Builds a header value for {@code payload} signed at the current clock time. */
    public String sign(byte[] payload) {
        long t = clock.instant().getEpochSecond();
        return "t=" + t + ",v1=" + new String(hexHmac(t, payload), StandardCharsets.US_ASCII);
    }

    private byte[] hexHmac(long timestamp, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            mac.update(payload);
            return HexFormat.of().formatHex(mac.doFinal()).getBytes(StandardCharsets.US_ASCII);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String message) {
            super(message);
        }
    }
}
