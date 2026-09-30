package com.ecom.catalog.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecom.platform.core.EcomException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryHierarchyTest {

    private final UUID root = UUID.randomUUID();
    private final UUID child = UUID.randomUUID();
    private final UUID grandChild = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final Map<UUID, UUID> parentOf = new HashMap<>(Map.of(child, root, grandChild, child));

    @Test
    void allowsMovingUnderUnrelatedBranch() {
        parentOf.put(other, null);
        assertThatCode(() -> CategoryHierarchy.requireNoCycle(child, other, parentOf)).doesNotThrowAnyException();
    }

    @Test
    void rejectsSelfParent() {
        assertThatThrownBy(() -> CategoryHierarchy.requireNoCycle(child, child, parentOf))
                .isInstanceOf(EcomException.class);
    }

    @Test
    void rejectsMovingUnderOwnDescendant() {
        assertThatThrownBy(() -> CategoryHierarchy.requireNoCycle(root, grandChild, parentOf))
                .isInstanceOf(EcomException.class)
                .hasMessageContaining("descendant");
    }

    @Test
    void detectsCorruptTreeWithoutLooping() {
        parentOf.put(root, grandChild);
        assertThatThrownBy(() -> CategoryHierarchy.requireNoCycle(other, child, parentOf))
                .isInstanceOf(EcomException.class)
                .hasMessageContaining("inconsistent");
    }
}
