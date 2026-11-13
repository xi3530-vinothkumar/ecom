package com.ecom.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ecom.platform.security.JwtIssuer;
import com.ecom.user.domain.RefreshToken;
import com.ecom.user.domain.Roles;
import com.ecom.user.domain.User;
import com.ecom.user.infrastructure.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenIssuerTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private final JwtIssuer jwtIssuer = mock(JwtIssuer.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final AuthProperties props = new AuthProperties(
            false, Duration.ofDays(14), Duration.ofDays(30), "refresh_token", "/auth", null, null,
            new AuthProperties.Lockout(5, Duration.ofMinutes(15), Duration.ofMinutes(15)));

    private TokenIssuer issuer;
    private User user;

    @BeforeEach
    void setUp() {
        issuer = new TokenIssuer(jwtIssuer, refreshTokens, props, Clock.fixed(NOW, ZoneOffset.UTC));
        user = new User(UUID.randomUUID(), "a@example.com", "hash", "A", null, Set.of(Roles.CUSTOMER), NOW);
        when(jwtIssuer.issue(anyString(), any())).thenReturn(
                new JwtIssuer.IssuedToken("jwt", "jti", NOW.plus(Duration.ofMinutes(15))));
        when(refreshTokens.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private RefreshToken presented(Instant familyExpiresAt) {
        return new RefreshToken(UUID.randomUUID(), user.getId(), "h", UUID.randomUUID(), NOW.minus(Duration.ofDays(1)),
                NOW.plusSeconds(1), familyExpiresAt);
    }

    @Test
    void loginStartsFamilyEndingAfterMaxLifetime() {
        RefreshToken t = issuer.startFamily(user).refreshToken();

        assertThat(t.getFamilyExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(t.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
    }

    @Test
    void refreshKeepsFamilyAndItsAbsoluteEnd() {
        RefreshToken old = presented(NOW.plus(Duration.ofDays(20)));

        TokenIssuer.Minted next = issuer.continueFamily(user, old);

        assertThat(next.refreshToken().getFamilyId()).isEqualTo(old.getFamilyId());
        assertThat(next.refreshToken().getFamilyExpiresAt()).isEqualTo(old.getFamilyExpiresAt());
        assertThat(next.refreshToken().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
    }

    @Test
    void refreshNearTheEndIsCappedAtFamilyExpiry() {
        Instant end = NOW.plus(Duration.ofHours(3));

        TokenIssuer.Minted next = issuer.continueFamily(user, presented(end));

        assertThat(next.refreshToken().getExpiresAt()).isEqualTo(end);
        assertThat(next.tokens().refreshTokenExpiresAt()).isEqualTo(end);
    }
}
