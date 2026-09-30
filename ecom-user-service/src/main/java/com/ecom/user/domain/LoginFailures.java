package com.ecom.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-account failed-login counter with a fixed window. Reaching the limit inside the window
 * locks the account; the counter restarts after the lock so a locked-out attacker gets a fresh
 * (still limited) budget only after the lock expires.
 */
@Entity
@Table(name = "login_failures")
public class LoginFailures {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "window_started_at")
    private Instant windowStartedAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    protected LoginFailures() {}

    public LoginFailures(UUID userId) {
        this.userId = Objects.requireNonNull(userId);
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /** Records a failed attempt; returns true when this failure locked the account. */
    public boolean recordFailure(Instant now, LockoutPolicy policy) {
        if (windowStartedAt == null || !now.isBefore(windowStartedAt.plus(policy.window()))) {
            windowStartedAt = now;
            failureCount = 0;
        }
        failureCount++;
        if (failureCount >= policy.maxFailures()) {
            lockedUntil = now.plus(policy.lockDuration());
            failureCount = 0;
            windowStartedAt = null;
            return true;
        }
        return false;
    }

    public void recordSuccess() {
        failureCount = 0;
        windowStartedAt = null;
        lockedUntil = null;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }
}
