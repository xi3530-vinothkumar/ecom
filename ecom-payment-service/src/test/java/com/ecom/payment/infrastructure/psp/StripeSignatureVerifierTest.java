package com.ecom.payment.infrastructure.psp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecom.payment.infrastructure.psp.StripeSignatureVerifier.InvalidSignatureException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class StripeSignatureVerifierTest {

    private static final String SECRET = "whsec_test_secret";
    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    private static final byte[] BODY = "{\"id\":\"evt_1\",\"type\":\"payment_intent.amount_capturable_updated\"}"
            .getBytes(StandardCharsets.UTF_8);

    private final StripeSignatureVerifier verifier = verifierAt(NOW, SECRET);

    private static StripeSignatureVerifier verifierAt(Instant now, String secret) {
        return new StripeSignatureVerifier(secret, Duration.ofMinutes(5), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void acceptsSignatureItProduced() {
        String header = verifier.sign(BODY);
        assertThat(header).startsWith("t=" + NOW.getEpochSecond() + ",v1=");
        assertThatCode(() -> verifier.verify(BODY, header)).doesNotThrowAnyException();
    }

    @Test
    void matchesStripeReferenceComputation() throws Exception {
        // independent HMAC over "t.body" with the whole whsec_ string as key, exactly as Stripe documents
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signed = NOW.getEpochSecond() + "." + new String(BODY, StandardCharsets.UTF_8);
        String hex = java.util.HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        assertThatCode(() -> verifier.verify(BODY, "t=" + NOW.getEpochSecond() + ",v1=" + hex + ",v0=ignored"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsTamperedBody() {
        String header = verifier.sign(BODY);
        byte[] tampered = new String(BODY, StandardCharsets.UTF_8).replace("evt_1", "evt_2").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verifier.verify(tampered, header))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageContaining("mismatch");
    }

    @Test
    void rejectsTamperedTimestamp() {
        String header = verifier.sign(BODY).replace("t=" + NOW.getEpochSecond(), "t=" + (NOW.getEpochSecond() - 1));
        assertThatThrownBy(() -> verifier.verify(BODY, header)).isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void rejectsSignatureFromAnotherSecret() {
        String header = verifierAt(NOW, "whsec_other").sign(BODY);
        assertThatThrownBy(() -> verifier.verify(BODY, header)).isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void rejectsExpiredSignature() {
        String header = verifierAt(NOW.minus(Duration.ofMinutes(5)).minusSeconds(1), SECRET).sign(BODY);
        assertThatThrownBy(() -> verifier.verify(BODY, header))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageContaining("tolerance");
    }

    @Test
    void rejectsSignatureTooFarInTheFuture() {
        String header = verifierAt(NOW.plus(Duration.ofMinutes(6)), SECRET).sign(BODY);
        assertThatThrownBy(() -> verifier.verify(BODY, header)).isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void acceptsSignatureAtToleranceBoundary() {
        String header = verifierAt(NOW.minus(Duration.ofMinutes(5)), SECRET).sign(BODY);
        assertThatCode(() -> verifier.verify(BODY, header)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAnyMatchingV1DuringSecretRotation() {
        String valid = verifier.sign(BODY);
        String v1 = valid.substring(valid.indexOf("v1=") + 3);
        String header = "t=" + NOW.getEpochSecond() + ",v1=" + "0".repeat(64) + ",v1=" + v1;
        assertThatCode(() -> verifier.verify(BODY, header)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNearMissesOfEveryShapeWithoutEarlyExit() {
        String valid = verifier.sign(BODY);
        String prefix = valid.substring(0, valid.length() - 1);
        char last = valid.charAt(valid.length() - 1);
        String lastByteFlipped = prefix + (last == '0' ? '1' : '0');
        String truncated = prefix;
        String extended = valid + "0";
        String uppercase = "t=" + NOW.getEpochSecond() + ",v1=" + valid.substring(valid.indexOf("v1=") + 3).toUpperCase();
        for (String header : new String[] {lastByteFlipped, truncated, extended}) {
            assertThatThrownBy(() -> verifier.verify(BODY, header)).isInstanceOf(InvalidSignatureException.class);
        }
        if (!uppercase.equals(valid)) {
            assertThatThrownBy(() -> verifier.verify(BODY, uppercase)).isInstanceOf(InvalidSignatureException.class);
        }
    }

    @Test
    void rejectsMissingOrMalformedHeaders() {
        for (String header : new String[] {null, "", "   ", "garbage", "t=abc,v1=00", "v1=00", "t=" + NOW.getEpochSecond(),
                "t=" + NOW.getEpochSecond() + ",v0=deadbeef"}) {
            assertThatThrownBy(() -> verifier.verify(BODY, header))
                    .as("header %s", header)
                    .isInstanceOf(InvalidSignatureException.class);
        }
    }

    @Test
    void refusesBlankSecret() {
        assertThatThrownBy(() -> verifierAt(NOW, " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
