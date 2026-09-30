package com.ecom.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoginFailuresTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final LockoutPolicy POLICY = new LockoutPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15));

    @Test
    void fifthFailureWithinWindowLocksForLockDuration() {
        LoginFailures f = new LoginFailures(UUID.randomUUID());
        for (int i = 0; i < 4; i++) {
            assertThat(f.recordFailure(T0.plusSeconds(i * 60L), POLICY)).isFalse();
        }
        assertThat(f.isLocked(T0.plusSeconds(240))).isFalse();

        assertThat(f.recordFailure(T0.plusSeconds(300), POLICY)).isTrue();

        assertThat(f.isLocked(T0.plusSeconds(300))).isTrue();
        assertThat(f.isLocked(T0.plusSeconds(300).plus(Duration.ofMinutes(15)).minusMillis(1))).isTrue();
        assertThat(f.isLocked(T0.plusSeconds(300).plus(Duration.ofMinutes(15)))).isFalse();
    }

    @Test
    void failuresOutsideWindowDoNotAccumulate() {
        LoginFailures f = new LoginFailures(UUID.randomUUID());
        for (int i = 0; i < 4; i++) {
            f.recordFailure(T0, POLICY);
        }
        // window elapsed: counter restarts at 1
        assertThat(f.recordFailure(T0.plus(Duration.ofMinutes(15)), POLICY)).isFalse();
        assertThat(f.getFailureCount()).isEqualTo(1);
        assertThat(f.isLocked(T0.plus(Duration.ofMinutes(15)))).isFalse();
    }

    @Test
    void successResetsCounterAndLock() {
        LoginFailures f = new LoginFailures(UUID.randomUUID());
        for (int i = 0; i < 4; i++) {
            f.recordFailure(T0, POLICY);
        }
        f.recordSuccess();
        assertThat(f.getFailureCount()).isZero();
        assertThat(f.recordFailure(T0.plusSeconds(1), POLICY)).isFalse();
        assertThat(f.getFailureCount()).isEqualTo(1);
    }

    @Test
    void afterLockExpiresAttackerGetsFreshButLimitedBudget() {
        LoginFailures f = new LoginFailures(UUID.randomUUID());
        for (int i = 0; i < 5; i++) {
            f.recordFailure(T0, POLICY);
        }
        Instant afterLock = T0.plus(Duration.ofMinutes(16));
        assertThat(f.isLocked(afterLock)).isFalse();
        for (int i = 0; i < 4; i++) {
            assertThat(f.recordFailure(afterLock, POLICY)).isFalse();
        }
        assertThat(f.recordFailure(afterLock, POLICY)).isTrue();
        assertThat(f.isLocked(afterLock)).isTrue();
    }
}
