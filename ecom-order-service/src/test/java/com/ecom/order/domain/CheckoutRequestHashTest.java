package com.ecom.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CheckoutRequestHashTest {

    @Test
    void sameBodySameHash() {
        assertThat(CheckoutRequestHash.of("pm_card_visa", "addr-1")).isEqualTo(CheckoutRequestHash.of("pm_card_visa", "addr-1"));
        assertThat(CheckoutRequestHash.of("pm_card_visa", "addr-1")).hasSize(64);
    }

    @Test
    void differentBodyDifferentHash() {
        assertThat(CheckoutRequestHash.of("pm_card_visa", "addr-1")).isNotEqualTo(CheckoutRequestHash.of("pm_card_visa", "addr-2"));
    }

    @Test
    void fieldBoundariesDoNotCollide() {
        assertThat(CheckoutRequestHash.of("ab", "c")).isNotEqualTo(CheckoutRequestHash.of("a", "bc"));
    }
}
