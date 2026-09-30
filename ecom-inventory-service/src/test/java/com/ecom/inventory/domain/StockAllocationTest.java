package com.ecom.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StockAllocationTest {

    private static StockLevel level(String sku, int onHand, int reserved) {
        return new StockLevel(sku, StockLevel.DEFAULT_WAREHOUSE, onHand, reserved, 1, Instant.EPOCH);
    }

    @Test
    void aggregatesDuplicateSkusSortedBySku() {
        var totals = SkuQuantity.aggregate(List.of(
                new SkuQuantity("B", 1), new SkuQuantity("A", 2), new SkuQuantity("B", 3)));
        assertThat(totals).containsExactly(Map.entry("A", 2), Map.entry("B", 4));
    }

    @Test
    void rejectsInvalidLines() {
        assertThatThrownBy(() -> new SkuQuantity("A", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkuQuantity("A", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkuQuantity(" ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkuQuantity("x".repeat(65), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkuQuantity.aggregate(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkuQuantity.aggregate(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aggregationOverflowIsRejected() {
        assertThatThrownBy(() -> SkuQuantity.aggregate(List.of(
                        new SkuQuantity("A", Integer.MAX_VALUE), new SkuQuantity("A", 1))))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void wholeOrderFitsWhenEveryLineFits() {
        var requested = SkuQuantity.aggregate(List.of(new SkuQuantity("A", 3), new SkuQuantity("B", 5)));
        assertThat(StockAllocation.shortSkus(requested, Map.of("A", level("A", 5, 2), "B", level("B", 5, 0))))
                .isEmpty();
    }

    @Test
    void reportsShortAndUnknownSkus() {
        var requested = SkuQuantity.aggregate(List.of(
                new SkuQuantity("C", 1), new SkuQuantity("A", 4), new SkuQuantity("B", 1)));
        assertThat(StockAllocation.shortSkus(requested, Map.of("A", level("A", 5, 2), "B", level("B", 1, 0))))
                .containsExactly("A", "C");
    }

    @Test
    void availabilityIsOnHandMinusReserved() {
        StockLevel l = level("A", 5, 5);
        assertThat(l.available()).isZero();
        assertThat(l.inStock()).isFalse();
        assertThat(l.canReserve(1)).isFalse();
        assertThat(level("A", 5, 4).canReserve(1)).isTrue();
    }
}
