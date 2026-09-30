package com.ecom.cart.domain;

import java.util.Map;
import java.util.TreeMap;

/**
 * Guest -> user merge on login: max quantity wins per SKU. Summing would silently double items the
 * shopper already added on another device.
 */
public final class CartMergePolicy {

    /**
     * @param maxLines cap on distinct SKUs in the merged cart; guest-only SKUs beyond it are dropped
     *     (in SKU order), existing user lines are never removed
     * @return the SKU -> quantity entries that must be written to the user cart (only changed ones)
     */
    public static Map<String, Integer> changesFor(
            Map<String, Integer> userCart, Map<String, Integer> guestCart, int maxLines) {
        Map<String, Integer> changes = new TreeMap<>();
        int lines = userCart.size();
        for (Map.Entry<String, Integer> e : new TreeMap<>(guestCart).entrySet()) {
            Integer guestQty = e.getValue();
            if (guestQty == null || guestQty <= 0) {
                continue;
            }
            Integer userQty = userCart.get(e.getKey());
            if (userQty == null) {
                if (lines >= maxLines) {
                    continue;
                }
                lines++;
                changes.put(e.getKey(), guestQty);
            } else if (guestQty > userQty) {
                changes.put(e.getKey(), guestQty);
            }
        }
        return changes;
    }

    public static Map<String, Integer> merge(Map<String, Integer> userCart, Map<String, Integer> guestCart, int maxLines) {
        Map<String, Integer> merged = new TreeMap<>(userCart);
        merged.putAll(changesFor(userCart, guestCart, maxLines));
        return merged;
    }

    private CartMergePolicy() {}
}
