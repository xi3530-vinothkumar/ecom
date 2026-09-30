package com.ecom.user.application;

import com.ecom.platform.security.JwtIssuer;
import com.ecom.user.domain.OpaqueTokens;
import com.ecom.user.domain.RefreshToken;
import com.ecom.user.domain.User;
import com.ecom.user.infrastructure.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Mints an access token plus a new refresh token in the given family (caller's transaction). */
@Component
public class TokenIssuer {

    private final JwtIssuer jwtIssuer;
    private final RefreshTokenRepository refreshTokens;
    private final AuthProperties props;
    private final Clock clock;

    public TokenIssuer(JwtIssuer jwtIssuer, RefreshTokenRepository refreshTokens, AuthProperties props, Clock clock) {
        this.jwtIssuer = jwtIssuer;
        this.refreshTokens = refreshTokens;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Minted mint(User user, UUID familyId) {
        Instant now = clock.instant();
        String raw = OpaqueTokens.generate();
        RefreshToken refresh = new RefreshToken(
                UUID.randomUUID(), user.getId(), OpaqueTokens.sha256Hex(raw), familyId, now,
                now.plus(props.refreshTokenTtl()));
        refreshTokens.save(refresh);
        JwtIssuer.IssuedToken access = jwtIssuer.issue(user.getId().toString(), user.getRoles());
        long expiresIn = Math.max(0, Duration.between(now, access.expiresAt()).toSeconds());
        IssuedTokens tokens = new IssuedTokens(
                user.getId(), user.getRoles(), access.token(), expiresIn, raw, refresh.getExpiresAt());
        return new Minted(tokens, refresh);
    }

    public record Minted(IssuedTokens tokens, RefreshToken refreshToken) {}
}
