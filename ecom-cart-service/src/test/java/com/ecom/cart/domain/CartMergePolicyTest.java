package com.ecom.cart.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CartMergePolicyTest {

    @Test
    void maxQuantityWinsPerSku() {
        var merged = CartMergePolicy.merge(Map.of("A", 2, "B", 1), Map.of("A", 1, "B", 3, "C", 4), 100);
        assertThat(merged).containsExactly(Map.entry("A", 2), Map.entry("B", 3), Map.entry("C", 4));
    }

    @Test
    void onlyChangedEntriesAreWritten() {
        assertThat(CartMergePolicy.changesFor(Map.of("A", 2, "B", 1), Map.of("A", 1, "B", 3, "C", 4), 100))
                .containsExactly(Map.entry("B", 3), Map.entry("C", 4));
    }

    @Test
    void equalQuantitiesAreNotSummed() {
        assertThat(CartMergePolicy.merge(Map.of("A", 2), Map.of("A", 2), 100)).containsExactly(Map.entry("A", 2));
        assertThat(CartMergePolicy.changesFor(Map.of("A", 2), Map.of("A", 2), 100)).isEmpty();
    }

    @Test
    void emptySidesAreHandled() {
        assertThat(CartMergePolicy.merge(Map.of(), Map.of("A", 1), 100)).containsExactly(Map.entry("A", 1));
        assertThat(CartMergePolicy.merge(Map.of("A", 1), Map.of(), 100)).containsExactly(Map.entry("A", 1));
    }

    @Test
    void nonPositiveGuestQuantitiesAreIgnored() {
        assertThat(CartMergePolicy.changesFor(Map.of(), Map.of("A", 0, "B", -2), 100)).isEmpty();
    }

    @Test
    void lineCapDropsNewGuestSkusButStillRaisesExistingOnes() {
        var changes = CartMergePolicy.changesFor(Map.of("A", 1, "B", 1), Map.of("A", 5, "C", 1, "D", 1), 3);
        assertThat(changes).containsExactly(Map.entry("A", 5), Map.entry("C", 1));
    }
}
