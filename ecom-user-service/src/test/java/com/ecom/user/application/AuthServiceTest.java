package com.ecom.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecom.platform.core.EcomException;
import com.ecom.platform.core.ErrorCode;
import com.ecom.user.domain.LoginFailures;
import com.ecom.user.domain.OpaqueTokens;
import com.ecom.user.domain.RefreshToken;
import com.ecom.user.domain.Roles;
import com.ecom.user.domain.User;
import com.ecom.user.infrastructure.LoginFailuresRepository;
import com.ecom.user.infrastructure.RefreshTokenRepository;
import com.ecom.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private final UserRepository users = mock(UserRepository.class);
    private final LoginFailuresRepository loginFailures = mock(LoginFailuresRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final TokenIssuer tokenIssuer = mock(TokenIssuer.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuthProperties props = new AuthProperties(
            false, Duration.ofDays(14), Duration.ofDays(30), "refresh_token", "/auth", null, null,
            new AuthProperties.Lockout(5, Duration.ofMinutes(15), Duration.ofMinutes(15)));

    private AuthService service;
    private User user;

    @BeforeEach
    void setUp() {
        when(encoder.encode(anyString())).thenReturn("dummy-hash");
        service = new AuthService(users, loginFailures, refreshTokens, tokenIssuer, encoder, props,
                Clock.fixed(NOW, ZoneOffset.UTC));
        user = new User(UUID.randomUUID(), "a@example.com", "hash", "A", null, Set.of(Roles.CUSTOMER), NOW);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
    }

    private RefreshToken storedToken(String raw) {
        RefreshToken t = new RefreshToken(UUID.randomUUID(), user.getId(), OpaqueTokens.sha256Hex(raw),
                UUID.randomUUID(), NOW.minusSeconds(60), NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(29)));
        when(refreshTokens.findByTokenHashForUpdate(OpaqueTokens.sha256Hex(raw))).thenReturn(Optional.of(t));
        return t;
    }

    private TokenIssuer.Minted minted(UUID familyId) {
        RefreshToken next = new RefreshToken(UUID.randomUUID(), user.getId(), "next", familyId, NOW, NOW.plus(Duration.ofDays(14)),
                NOW.plus(Duration.ofDays(29)));
        return new TokenIssuer.Minted(
                new IssuedTokens(user.getId(), user.getRoles(), "jwt", 900, "raw-next", next.getExpiresAt()), next);
    }

    @Test
    void activeTokenIsRotatedWithinSameFamily() {
        RefreshToken presented = storedToken("raw-1");
        TokenIssuer.Minted next = minted(presented.getFamilyId());
        when(tokenIssuer.continueFamily(user, presented)).thenReturn(next);

        IssuedTokens result = service.refresh("raw-1");

        assertThat(result.refreshToken()).isEqualTo("raw-next");
        assertThat(presented.statusAt(NOW)).isEqualTo(RefreshToken.Status.ROTATED);
        assertThat(presented.getReplacedBy()).isEqualTo(next.refreshToken().getId());
        verify(refreshTokens, never()).revokeFamily(any(), any());
    }

    @Test
    void reuseOfRotatedTokenRevokesWholeFamily() {
        RefreshToken presented = storedToken("raw-1");
        presented.rotateTo(UUID.randomUUID(), NOW.minusSeconds(10));

        assertThatThrownBy(() -> service.refresh("raw-1"))
                .isInstanceOfSatisfying(EcomException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));

        verify(refreshTokens).revokeFamily(presented.getFamilyId(), NOW);
        verify(tokenIssuer, never()).continueFamily(any(), any());
    }

    @Test
    void expiredTokenIsRejectedWithoutRevokingFamily() {
        RefreshToken t = new RefreshToken(UUID.randomUUID(), user.getId(), OpaqueTokens.sha256Hex("old"),
                UUID.randomUUID(), NOW.minus(Duration.ofDays(15)), NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(15)));
        when(refreshTokens.findByTokenHashForUpdate(OpaqueTokens.sha256Hex("old"))).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.refresh("old")).isInstanceOf(EcomException.class);
        verify(refreshTokens, never()).revokeFamily(any(), any());
        verify(tokenIssuer, never()).continueFamily(any(), any());
    }

    @Test
    void unknownTokenIsRejected() {
        when(refreshTokens.findByTokenHashForUpdate(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.refresh("nope")).isInstanceOf(EcomException.class);
        assertThatThrownBy(() -> service.refresh(" ")).isInstanceOf(EcomException.class);
    }

    @Test
    void unknownEmailStillPaysForOnePasswordVerification() {
        when(users.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("Ghost@Example.com", "pw"))
                .hasMessage(AuthService.INVALID_CREDENTIALS);
        verify(encoder).matches("pw", "dummy-hash");
    }

    @Test
    void fifthWrongPasswordLocksAndLockedAccountSkipsPasswordCheck() {
        LoginFailures failures = new LoginFailures(user.getId());
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(user));
        when(loginFailures.findForUpdate(user.getId())).thenReturn(Optional.of(failures));
        when(encoder.matches("wrong", "hash")).thenReturn(false);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.login("a@example.com", "wrong"))
                    .hasMessage(AuthService.INVALID_CREDENTIALS);
        }
        assertThat(failures.isLocked(NOW)).isTrue();

        when(encoder.matches("right", "hash")).thenReturn(true);
        assertThatThrownBy(() -> service.login("a@example.com", "right"))
                .hasMessage(AuthService.INVALID_CREDENTIALS);
        verify(encoder, never()).matches("right", "hash");
        verify(tokenIssuer, never()).startFamily(any());
    }

    @Test
    void successfulLoginStartsNewFamilyAndResetsFailures() {
        LoginFailures failures = new LoginFailures(user.getId());
        failures.recordFailure(NOW.minusSeconds(5), props.lockout().toPolicy());
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(user));
        when(loginFailures.findForUpdate(user.getId())).thenReturn(Optional.of(failures));
        when(encoder.matches("right", "hash")).thenReturn(true);
        when(tokenIssuer.startFamily(user)).thenReturn(minted(UUID.randomUUID()));

        IssuedTokens tokens = service.login(" A@example.com ", "right");

        assertThat(tokens.accessToken()).isEqualTo("jwt");
        assertThat(failures.getFailureCount()).isZero();
    }
}
