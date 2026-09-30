package com.ecom.inventory.domain;

import static com.ecom.inventory.domain.ReservationStatus.COMMITTED;
import static com.ecom.inventory.domain.ReservationStatus.EXPIRING;
import static com.ecom.inventory.domain.ReservationStatus.RELEASED;
import static com.ecom.inventory.domain.ReservationStatus.RESERVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReservationStatusTest {

    @Test
    void reservedCanExpireCommitOrRelease() {
        assertThat(RESERVED.canTransitionTo(EXPIRING)).isTrue();
        assertThat(RESERVED.canTransitionTo(COMMITTED)).isTrue();
        assertThat(RESERVED.canTransitionTo(RELEASED)).isTrue();
        assertThat(RESERVED.canTransitionTo(RESERVED)).isFalse();
    }

    @Test
    void expiringAwaitsOrdersDecisionAndNeverGoesBack() {
        assertThat(EXPIRING.canTransitionTo(COMMITTED)).isTrue();
        assertThat(EXPIRING.canTransitionTo(RELEASED)).isTrue();
        assertThat(EXPIRING.canTransitionTo(RESERVED)).isFalse();
        assertThat(EXPIRING.canTransitionTo(EXPIRING)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"COMMITTED", "RELEASED"})
    void terminalStatesAllowNoTransition(ReservationStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(terminal.holdsStock()).isFalse();
        for (ReservationStatus target : ReservationStatus.values()) {
            assertThat(terminal.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    void sourcesOfEachTarget() {
        assertThat(ReservationStatus.sourcesOf(COMMITTED)).containsExactlyInAnyOrder(RESERVED, EXPIRING);
        assertThat(ReservationStatus.sourcesOf(RELEASED)).containsExactlyInAnyOrder(RESERVED, EXPIRING);
        assertThat(ReservationStatus.sourcesOf(EXPIRING)).containsExactly(RESERVED);
        assertThat(ReservationStatus.sourcesOf(RESERVED)).isEmpty();
    }

    @Test
    void holdsStockUntilTerminal() {
        assertThat(RESERVED.holdsStock()).isTrue();
        assertThat(EXPIRING.holdsStock()).isTrue();
    }

    @Test
    void aggregateOfUniformLinesIsThatStatus() {
        for (ReservationStatus s : ReservationStatus.values()) {
            assertThat(ReservationStatus.aggregate(List.of(s, s, s))).isEqualTo(s);
        }
    }

    @Test
    void aggregateOfMixedLinesReportsLeastFavourable() {
        assertThat(ReservationStatus.aggregate(List.of(RESERVED, EXPIRING))).isEqualTo(EXPIRING);
        assertThat(ReservationStatus.aggregate(List.of(COMMITTED, EXPIRING))).isEqualTo(EXPIRING);
        assertThat(ReservationStatus.aggregate(List.of(COMMITTED, RELEASED))).isEqualTo(RELEASED);
        assertThat(ReservationStatus.aggregate(List.of(COMMITTED, RESERVED))).isEqualTo(RESERVED);
    }

    @Test
    void aggregateRejectsNoLines() {
        assertThatThrownBy(() -> ReservationStatus.aggregate(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
