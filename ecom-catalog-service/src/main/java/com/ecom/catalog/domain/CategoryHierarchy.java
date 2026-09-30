package com.ecom.catalog.domain;

import com.ecom.platform.core.EcomException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Guards the category tree against cycles when a category is re-parented. */
public final class CategoryHierarchy {

    /**
     * @param parentOf current child -> parent links of every category (roots map to null or are absent)
     */
    public static void requireNoCycle(UUID categoryId, UUID newParentId, Map<UUID, UUID> parentOf) {
        Set<UUID> seen = new HashSet<>();
        for (UUID cursor = newParentId; cursor != null; cursor = parentOf.get(cursor)) {
            if (cursor.equals(categoryId)) {
                throw EcomException.validation("Category " + categoryId + " cannot be moved under itself or a descendant");
            }
            if (!seen.add(cursor)) {
                // pre-existing cycle in the data; refuse rather than loop forever
                throw EcomException.validation("Category tree is inconsistent around " + cursor);
            }
        }
    }

    private CategoryHierarchy() {}
}
