package com.ecom.user.application;

import com.ecom.platform.core.EcomException;
import com.ecom.user.domain.Emails;
import com.ecom.user.domain.LockoutPolicy;
import com.ecom.user.domain.LoginFailures;
import com.ecom.user.domain.OpaqueTokens;
import com.ecom.user.domain.RefreshToken;
import com.ecom.user.domain.User;
import com.ecom.user.infrastructure.LoginFailuresRepository;
import com.ecom.user.infrastructure.RefreshTokenRepository;
import com.ecom.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login, refresh-token rotation with reuse detection, logout. Failure paths deliberately commit
 * (noRollbackFor): a failed attempt counter or a family revocation must persist even though the
 * caller gets a 401.
 */
@Service
public class AuthService {

    static final String INVALID_CREDENTIALS = "Invalid email or password";
    static final String INVALID_REFRESH_TOKEN = "Invalid or expired refresh token";

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final LoginFailuresRepository loginFailures;
    private final RefreshTokenRepository refreshTokens;
    private final TokenIssuer tokenIssuer;
    private final PasswordEncoder passwordEncoder;
    private final LockoutPolicy lockoutPolicy;
    private final Clock clock;
    /** Verified against for unknown e-mails so both paths cost one Argon2 verification. */
    private final String dummyHash;

    public AuthService(
            UserRepository users,
            LoginFailuresRepository loginFailures,
            RefreshTokenRepository refreshTokens,
            TokenIssuer tokenIssuer,
            PasswordEncoder passwordEncoder,
            AuthProperties props,
            Clock clock) {
        this.users = users;
        this.loginFailures = loginFailures;
        this.refreshTokens = refreshTokens;
        this.tokenIssuer = tokenIssuer;
        this.passwordEncoder = passwordEncoder;
        this.lockoutPolicy = props.lockout().toPolicy();
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(noRollbackFor = EcomException.class)
    public IssuedTokens login(String email, String password) {
        Optional<User> found = users.findByEmail(Emails.normalize(email));
        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            throw EcomException.unauthenticated(INVALID_CREDENTIALS);
        }
        User user = found.get();
        Instant now = clock.instant();
        LoginFailures failures = loginFailures.findForUpdate(user.getId())
                .orElseGet(() -> loginFailures.save(new LoginFailures(user.getId())));
        if (failures.isLocked(now)) {
            // no password check while locked: the lock must not become a correctness oracle
            log.info("Login rejected for locked account userId={} lockedUntil={}", user.getId(), failures.getLockedUntil());
            throw EcomException.unauthenticated(INVALID_CREDENTIALS);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            if (failures.recordFailure(now, lockoutPolicy)) {
                log.warn("Account locked after {} failed logins userId={} until={}",
                        lockoutPolicy.maxFailures(), user.getId(), failures.getLockedUntil());
            }
            throw EcomException.unauthenticated(INVALID_CREDENTIALS);
        }
        failures.recordSuccess();
        if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            user.changePasswordHash(passwordEncoder.encode(password), now);
        }
        return tokenIssuer.mint(user, UUID.randomUUID()).tokens();
    }

    @Transactional(noRollbackFor = EcomException.class)
    public IssuedTokens refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw EcomException.unauthenticated(INVALID_REFRESH_TOKEN);
        }
        RefreshToken presented = refreshTokens.findByTokenHashForUpdate(OpaqueTokens.sha256Hex(rawRefreshToken))
                .orElseThrow(() -> EcomException.unauthenticated(INVALID_REFRESH_TOKEN));
        Instant now = clock.instant();
        return switch (presented.statusAt(now)) {
            case ACTIVE -> {
                User user = users.findById(presented.getUserId())
                        .orElseThrow(() -> EcomException.unauthenticated(INVALID_REFRESH_TOKEN));
                TokenIssuer.Minted next = tokenIssuer.mint(user, presented.getFamilyId());
                presented.rotateTo(next.refreshToken().getId(), now);
                yield next.tokens();
            }
            case ROTATED -> {
                int revoked = refreshTokens.revokeFamily(presented.getFamilyId(), now);
                log.warn("Refresh token reuse detected: revoked family={} userId={} activeTokensRevoked={}",
                        presented.getFamilyId(), presented.getUserId(), revoked);
                throw EcomException.unauthenticated(INVALID_REFRESH_TOKEN);
            }
            case REVOKED, EXPIRED -> throw EcomException.unauthenticated(INVALID_REFRESH_TOKEN);
        };
    }

    /** Revokes the presented token's whole family. Unknown/blank tokens are a no-op (idempotent logout). */
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokens.findByTokenHash(OpaqueTokens.sha256Hex(rawRefreshToken))
                .ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId(), clock.instant()));
    }
}
