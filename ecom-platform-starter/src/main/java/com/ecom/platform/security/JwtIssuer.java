package com.ecom.platform.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** RS256 access-token issuer + JWKS source. Claims: sub, roles, iss, aud, iat, exp, jti. */
public class JwtIssuer {

    public record IssuedToken(String token, String jti, Instant expiresAt) {}

    private final JwtProperties props;
    private final RSASSASigner signer;
    private final JWKSet jwks;
    private final Clock clock;

    public JwtIssuer(JwtProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        RSAPrivateCrtKey privateKey = PemKeys.privateKey(props.getPrivateKeyPem());
        this.signer = new RSASSASigner(privateKey);
        List<com.nimbusds.jose.jwk.JWK> keys = new ArrayList<>();
        keys.add(new RSAKey.Builder(PemKeys.publicKeyOf(privateKey))
                .keyID(props.getKeyId()).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build());
        for (JwtProperties.PublicKey prev : props.getPreviousPublicKeys()) {
            keys.add(new RSAKey.Builder(PemKeys.publicKey(prev.pem()))
                    .keyID(prev.keyId()).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build());
        }
        this.jwks = new JWKSet(keys);
    }

    public IssuedToken issue(String userId, Collection<String> roles) {
        Instant now = clock.instant();
        Instant exp = now.plus(props.getAccessTokenTtl());
        String jti = UUID.randomUUID().toString();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId)
                .issuer(props.getIssuer())
                .audience(props.getAudience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .jwtID(jti)
                .claim("roles", List.copyOf(roles))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(props.getKeyId()).type(JOSEObjectType.JWT).build(),
                claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT signing failed", e);
        }
        return new IssuedToken(jwt.serialize(), jti, exp);
    }

    /** Public JWKS document for {@code /.well-known/jwks.json}. */
    public Map<String, Object> jwks() {
        return jwks.toJSONObject(true);
    }
}
