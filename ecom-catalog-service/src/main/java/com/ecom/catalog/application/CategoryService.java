package com.ecom.catalog.application;

import com.ecom.catalog.domain.Category;
import com.ecom.catalog.domain.CategoryHierarchy;
import com.ecom.catalog.infrastructure.CategoryRepository;
import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.EcomException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    private final CategoryRepository categories;
    private final Clock clock;

    public CategoryService(CategoryRepository categories, Clock clock) {
        this.categories = categories;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Category> list() {
        return categories.findAllByOrderByNameAsc();
    }

    @Transactional
    public Category create(String name, UUID parentId) {
        CallContext.requireRole(CallContext.ROLE_ADMIN);
        requireParent(parentId);
        return saveUnique(Category.create(name, parentId, clock.instant()), name);
    }

    @Transactional
    public Category update(UUID id, String name, UUID parentId) {
        CallContext.requireRole(CallContext.ROLE_ADMIN);
        Category category = categories.findById(id)
                .orElseThrow(() -> EcomException.notFound("Category " + id + " not found"));
        if (parentId != null) {
            requireParent(parentId);
            Map<UUID, UUID> parentOf = new HashMap<>();
            categories.findAll().forEach(c -> parentOf.put(c.getId(), c.getParentId()));
            CategoryHierarchy.requireNoCycle(id, parentId, parentOf);
        }
        category.rename(name, parentId, clock.instant());
        return saveUnique(category, name);
    }

    private void requireParent(UUID parentId) {
        if (parentId != null && !categories.existsById(parentId)) {
            throw EcomException.unprocessable("Parent category " + parentId + " does not exist");
        }
    }

    private Category saveUnique(Category category, String name) {
        try {
            return categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException _) {
            throw EcomException.conflict("A category named '" + name.strip() + "' already exists under this parent");
        }
    }
}
