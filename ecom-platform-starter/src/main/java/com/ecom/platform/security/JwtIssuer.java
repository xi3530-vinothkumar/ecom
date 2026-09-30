package com.ecom.platform.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * RS256 access-token issuer (Spring Security {@link JwtEncoder}) + JWKS source.
 * Claims: sub, roles, iss, aud, iat, exp, jti.
 */
public class JwtIssuer {

    public record IssuedToken(String token, String jti, Instant expiresAt) {}

    private final JwtProperties props;
    private final JwtEncoder encoder;
    private final JWKSet publicJwks;
    private final Clock clock;

    public JwtIssuer(JwtProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        RSAPrivateCrtKey privateKey = PemKeys.privateKey(props.privateKeyPem());
        RSAKey signingKey = rsaKey(props.keyId(), new RSAKey.Builder(PemKeys.publicKeyOf(privateKey)).privateKey(privateKey));
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
        List<JWK> keys = new ArrayList<>();
        keys.add(signingKey.toPublicJWK());
        for (JwtProperties.PublicKey prev : props.previousPublicKeys()) {
            keys.add(rsaKey(prev.keyId(), new RSAKey.Builder(PemKeys.publicKey(prev.pem()))));
        }
        this.publicJwks = new JWKSet(keys);
    }

    public IssuedToken issue(String userId, Collection<String> roles) {
        Instant now = clock.instant();
        Instant exp = now.plus(props.accessTokenTtl());
        String jti = UUID.randomUUID().toString();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId)
                .issuer(props.issuer())
                .audience(List.of(props.audience()))
                .issuedAt(now)
                .expiresAt(exp)
                .id(jti)
                .claim("roles", List.copyOf(roles))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(props.keyId()).type("JWT").build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, jti, exp);
    }

    /** Public JWKS document for {@code /.well-known/jwks.json}. */
    public Map<String, Object> jwks() {
        return publicJwks.toJSONObject(true);
    }

    private static RSAKey rsaKey(String keyId, RSAKey.Builder builder) {
        return builder.keyID(keyId).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
    }
}
