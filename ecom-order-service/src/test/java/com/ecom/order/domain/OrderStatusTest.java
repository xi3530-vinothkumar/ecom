package com.ecom.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderStatusTest {

    @Test
    void pendingMovesToEitherTerminalState() {
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.CONFIRMED)).isTrue();
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.PENDING)).isFalse();
        assertThat(OrderStatus.PENDING.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "CANCELLED"})
    void terminalStatesNeverMove(OrderStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (OrderStatus target : OrderStatus.values()) {
            assertThat(terminal.canTransitionTo(target)).as("%s -> %s", terminal, target).isFalse();
        }
    }

    @Test
    void cancelReasonsMatchEventContract() {
        assertThat(CancelReason.OUT_OF_STOCK.eventValue()).isEqualTo("OUT_OF_STOCK");
        assertThat(CancelReason.PAYMENT_DECLINED.eventValue()).isEqualTo("PAYMENT_DECLINED");
        assertThat(CancelReason.PAYMENT_TIMEOUT.eventValue()).isEqualTo("PAYMENT_TIMEOUT");
    }
}
