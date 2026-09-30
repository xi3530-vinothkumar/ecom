package com.ecom.platform.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Issuer-side JWT settings (User service only). Only the User service holds the private key; the
 * Gateway validates against the published JWKS. Rotation: add the new key as signing key, move the
 * old public key to {@code previousPublicKeys}, remove it after one access-token TTL.
 */
@ConfigurationProperties("ecom.jwt")
public class JwtProperties {

    private String issuer = "ecom-user-service";
    private String audience = "ecom";
    private String keyId;
    private String privateKeyPem;
    private Duration accessTokenTtl = Duration.ofMinutes(15);
    private List<PublicKey> previousPublicKeys = new ArrayList<>();

    public record PublicKey(String keyId, String pem) {}

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getPrivateKeyPem() {
        return privateKeyPem;
    }

    public void setPrivateKeyPem(String privateKeyPem) {
        this.privateKeyPem = privateKeyPem;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public List<PublicKey> getPreviousPublicKeys() {
        return previousPublicKeys;
    }

    public void setPreviousPublicKeys(List<PublicKey> previousPublicKeys) {
        this.previousPublicKeys = previousPublicKeys;
    }
}
