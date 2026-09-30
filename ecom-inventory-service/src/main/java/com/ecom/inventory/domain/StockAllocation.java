package com.ecom.inventory.domain;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/** All-or-nothing allocation rule for one order. */
public final class StockAllocation {

    /**
     * @param requested aggregated quantity per SKU
     * @param stock current (locked) stock rows; a SKU without a row is unknown and therefore short
     * @return SKUs that cannot be fully reserved, in SKU order; empty means the whole order fits
     */
    public static List<String> shortSkus(SortedMap<String, Integer> requested, Map<String, StockLevel> stock) {
        return requested.entrySet().stream()
                .filter(e -> {
                    StockLevel level = stock.get(e.getKey());
                    return level == null || !level.canReserve(e.getValue());
                })
                .map(Map.Entry::getKey)
                .toList();
    }

    private StockAllocation() {}
}
