package com.ecom.user.domain;

import java.time.Duration;

/** {@code maxFailures} failed logins within {@code window} lock the account for {@code lockDuration}. */
public record LockoutPolicy(int maxFailures, Duration window, Duration lockDuration) {

    public LockoutPolicy {
        if (maxFailures < 1 || window.isNegative() || window.isZero() || lockDuration.isNegative()) {
            throw new IllegalArgumentException("Invalid lockout policy");
        }
    }
}
