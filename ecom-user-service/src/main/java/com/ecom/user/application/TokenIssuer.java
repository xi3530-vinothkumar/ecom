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

/**
 * Mints an access token plus a refresh token (caller's transaction). A login starts a family with an
 * absolute end of {@code now + session-max-lifetime}; a refresh continues the presented token's
 * family under that same end. Each refresh token lives {@code refresh-token-ttl}, capped at the end.
 */
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
    public Minted startFamily(User user) {
        Instant now = clock.instant();
        return mint(user, UUID.randomUUID(), now.plus(props.sessionMaxLifetime()), now);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Minted continueFamily(User user, RefreshToken presented) {
        Instant now = clock.instant();
        Instant familyExpiresAt = presented.getFamilyExpiresAt() != null
                ? presented.getFamilyExpiresAt()
                : now.plus(props.sessionMaxLifetime());
        return mint(user, presented.getFamilyId(), familyExpiresAt, now);
    }

    private Minted mint(User user, UUID familyId, Instant familyExpiresAt, Instant now) {
        String raw = OpaqueTokens.generate();
        Instant expiresAt = min(now.plus(props.refreshTokenTtl()), familyExpiresAt);
        RefreshToken refresh = new RefreshToken(
                UUID.randomUUID(), user.getId(), OpaqueTokens.sha256Hex(raw), familyId, now, expiresAt,
                familyExpiresAt);
        refreshTokens.save(refresh);
        JwtIssuer.IssuedToken access = jwtIssuer.issue(user.getId().toString(), user.getRoles());
        long expiresIn = Math.max(0, Duration.between(now, access.expiresAt()).toSeconds());
        IssuedTokens tokens = new IssuedTokens(
                user.getId(), user.getRoles(), access.token(), expiresIn, raw, refresh.getExpiresAt());
        return new Minted(tokens, refresh);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    public record Minted(IssuedTokens tokens, RefreshToken refreshToken) {}
}
