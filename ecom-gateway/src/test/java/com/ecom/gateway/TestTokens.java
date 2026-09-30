package com.ecom.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Test RSA keys: {@code trusted} is published in the stub JWKS, {@code rogue} never is. */
final class TestTokens {

    static final String ISSUER = "ecom-user-service";
    static final String AUDIENCE = "ecom";

    private final RSAKey trusted = generate("test-kid");
    private final RSAKey rogue = generate("rogue-kid");

    String jwksJson() {
        return new JWKSet(trusted.toPublicJWK()).toString();
    }

    String valid(String sub, String... roles) {
        return sign(trusted, sub, ISSUER, AUDIENCE, Instant.now().plus(Duration.ofMinutes(15)), roles);
    }

    String expired(String sub) {
        return sign(trusted, sub, ISSUER, AUDIENCE, Instant.now().minus(Duration.ofMinutes(10)), "CUSTOMER");
    }

    String unknownKid(String sub) {
        return sign(rogue, sub, ISSUER, AUDIENCE, Instant.now().plus(Duration.ofMinutes(15)), "CUSTOMER");
    }

    String wrongAudience(String sub) {
        return sign(trusted, sub, ISSUER, "someone-else", Instant.now().plus(Duration.ofMinutes(15)), "CUSTOMER");
    }

    String wrongIssuer(String sub) {
        return sign(trusted, sub, "evil-issuer", AUDIENCE, Instant.now().plus(Duration.ofMinutes(15)), "CUSTOMER");
    }

    private static String sign(RSAKey key, String sub, String iss, String aud, Instant exp, String... roles) {
        Instant iat = exp.minus(Duration.ofMinutes(15));
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(sub).issuer(iss).audience(aud)
                .issueTime(Date.from(iat)).expirationTime(Date.from(exp))
                .jwtID(UUID.randomUUID().toString())
                .claim("roles", List.of(roles))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static RSAKey generate(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
