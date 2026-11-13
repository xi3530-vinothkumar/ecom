package com.ecom.platform.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Issuer-side JWT settings (User service only). Only the User service holds the private key; the
 * Gateway validates against the published JWKS. Rotation: add the new key as signing key, move the
 * old public key to {@code previousPublicKeys}, remove it after one access-token TTL.
 */
@ConfigurationProperties("ecom.jwt")
public record JwtProperties(
        @DefaultValue("ecom-user-service") String issuer,
        @DefaultValue("ecom") String audience,
        String keyId,
        String privateKeyPem,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue List<PublicKey> previousPublicKeys) {

    public record PublicKey(String keyId, String pem) {}

    public JwtProperties withPrivateKeyPem(String pem) {
        return new JwtProperties(issuer, audience, keyId, pem, accessTokenTtl, previousPublicKeys);
    }
}
