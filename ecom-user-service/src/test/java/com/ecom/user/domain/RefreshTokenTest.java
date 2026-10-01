package com.ecom.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static RefreshToken token() {
        return new RefreshToken(UUID.randomUUID(), UUID.randomUUID(), "h", UUID.randomUUID(), T0, T0.plus(Duration.ofDays(14)),
                T0.plus(Duration.ofDays(30)));
    }

    @Test
    void freshTokenIsActiveUntilExpiry() {
        RefreshToken t = token();
        assertThat(t.statusAt(T0)).isEqualTo(RefreshToken.Status.ACTIVE);
        assertThat(t.statusAt(T0.plus(Duration.ofDays(14)).minusMillis(1))).isEqualTo(RefreshToken.Status.ACTIVE);
        assertThat(t.statusAt(T0.plus(Duration.ofDays(14)))).isEqualTo(RefreshToken.Status.EXPIRED);
    }

    @Test
    void rotatedTokenReportsRotatedEvenAfterExpiry() {
        RefreshToken t = token();
        UUID successor = UUID.randomUUID();
        t.rotateTo(successor, T0.plusSeconds(60));

        assertThat(t.getReplacedBy()).isEqualTo(successor);
        assertThat(t.statusAt(T0.plusSeconds(61))).isEqualTo(RefreshToken.Status.ROTATED);
        assertThat(t.statusAt(T0.plus(Duration.ofDays(30)))).isEqualTo(RefreshToken.Status.ROTATED);
    }

    @Test
    void rotatedTokenCannotRotateAgain() {
        RefreshToken t = token();
        t.rotateTo(UUID.randomUUID(), T0.plusSeconds(1));
        assertThatThrownBy(() -> t.rotateTo(UUID.randomUUID(), T0.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expiredTokenCannotRotate() {
        RefreshToken t = token();
        assertThatThrownBy(() -> t.rotateTo(UUID.randomUUID(), T0.plus(Duration.ofDays(15))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tokenCannotOutliveItsFamily() {
        assertThatThrownBy(() -> new RefreshToken(UUID.randomUUID(), UUID.randomUUID(), "h", UUID.randomUUID(), T0,
                T0.plus(Duration.ofDays(14)), T0.plus(Duration.ofDays(13))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
