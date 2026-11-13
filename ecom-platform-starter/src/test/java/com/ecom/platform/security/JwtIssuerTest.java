package com.ecom.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtIssuerTest {

    static String pkcs8Pem() throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        byte[] der = gen.generateKeyPair().getPrivate().getEncoded();
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END PRIVATE KEY-----";
    }

    @Test
    void issuesRs256TokenVerifiableViaJwks() throws Exception {
        var props = new JwtProperties("ecom-user-service", "ecom", "k1", pkcs8Pem(), Duration.ofMinutes(15), List.of());
        var issuer = new JwtIssuer(props, Clock.systemUTC());

        var token = issuer.issue("user-1", List.of("CUSTOMER"));

        SignedJWT jwt = SignedJWT.parse(token.token());
        RSAKey key = (RSAKey) JWKSet.parse(issuer.jwks()).getKeyByKeyId("k1");
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jwt.verify(new RSASSAVerifier(key))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("user-1");
        assertThat(jwt.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("CUSTOMER");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("ecom");
        assertThat(issuer.jwks().toString()).doesNotContain("\"d\"");
    }
}
