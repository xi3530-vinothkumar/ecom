package com.ecom.catalog.application;

import com.ecom.catalog.domain.Product;
import com.ecom.catalog.domain.ProductDetails;
import com.ecom.catalog.domain.ProductRules;
import com.ecom.catalog.domain.ProductVariant;
import com.ecom.catalog.domain.VariantSpec;
import com.ecom.catalog.infrastructure.CategoryRepository;
import com.ecom.catalog.infrastructure.ProductRepository;
import com.ecom.catalog.infrastructure.ProductVariantRepository;
import com.ecom.common.events.EventTopics;
import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.EcomException;
import com.ecom.platform.kafka.OutboxWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin writes. Each one bumps the product version and emits the full state through the outbox. */
@Service
public class ProductAdminService {

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final CategoryRepository categories;
    private final OutboxWriter outbox;
    private final EntityManager em;
    private final Clock clock;

    public ProductAdminService(
            ProductRepository products,
            ProductVariantRepository variants,
            CategoryRepository categories,
            OutboxWriter outbox,
            EntityManager em,
            Clock clock) {
        this.products = products;
        this.variants = variants;
        this.categories = categories;
        this.outbox = outbox;
        this.em = em;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Product get(UUID id) {
        CallContext.requireRole(CallContext.ROLE_ADMIN);
        return loaded(products.findById(id).filter(p -> !p.isDeleted()).orElseThrow(() -> notFound(id)));
    }

    @Transactional
    public Product create(ProductDetails details, List<VariantSpec> specs) {
        CallContext.requireRole(CallContext.ROLE_ADMIN);
        ProductRules.validate(details, specs);
        requireCategory(details.categoryId());
        List<ProductVariant> taken = variants.findAllById(skus(specs));
        if (!taken.isEmpty()) {
            throw EcomException.conflict("SKU already exists: " + taken.getFirst().getSku());
        }
        Product product = Product.create(details, specs, clock.instant());
        em.persist(product);
        flushOrConflict();
        outbox.publish(EventTopics.PRODUCT_CREATED, product.getId().toString(), ProductEventMapper.changed(product));
        return product;
    }

    @Transactional
    public Product update(UUID id, Long expectedVersion, ProductDetails details, List<VariantSpec> specs) {
        CallContext.requireRole(CallContext.ROLE_ADMIN);
        ProductRules.validate(details, specs);
        Product product = lockLive(id);
        if (expectedVersion != null && expectedVersion != product.getVersion()) {
            throw EcomException.conflict(
                    "Product " + id + " was modified concurrently (expected version " + expectedVersion
                            + ", current " + product.getVersion() + ")");
        }
        requireCategory(details.categoryId());
        variants.findAllById(skus(specs)).stream()
                .filter(v -> !v.getProduct().getId().equals(id))
                .findFirst()
                .ifPresent(v -> {
                    throw EcomException.conflict("SKU " + v.getSku() + " belongs to another product");
                });
        product.revise(details, specs, clock.instant());
        flushOrConflict();
        outbox.publish(EventTopics.PRODUCT_UPDATED, id.toString(), ProductEventMapper.changed(product));
        return loaded(product);
    }

    @Transactional
    public void delete(UUID id) {
        CallContext.requireRole(CallContext.ROLE_ADMIN);
        Product product = lockLive(id);
        product.delete(clock.instant());
        outbox.publish(EventTopics.PRODUCT_DELETED, id.toString(), ProductEventMapper.deleted(product));
    }

    /** Registers an uploaded image; a product write like any other (version bump + product-updated). */
    @Transactional
    public Product addImage(UUID id, String s3Key, Integer position) {
        CallContext.requireRole(CallContext.ROLE_ADMIN);
        Product product = lockLive(id);
        product.addImage(s3Key, position, clock.instant());
        flushOrConflict();
        outbox.publish(EventTopics.PRODUCT_UPDATED, id.toString(), ProductEventMapper.changed(product));
        return loaded(product);
    }

    @Transactional(readOnly = true)
    public void requireLive(UUID id) {
        products.findById(id).filter(p -> !p.isDeleted()).orElseThrow(() -> notFound(id));
    }

    private Product lockLive(UUID id) {
        return products.findForUpdate(id).filter(p -> !p.isDeleted()).orElseThrow(() -> notFound(id));
    }

    /** Responses are mapped after the transaction closes (open-in-view is off). */
    private static Product loaded(Product p) {
        p.getVariants().size();
        p.getImages().size();
        return p;
    }

    private void requireCategory(UUID categoryId) {
        if (!categories.existsById(categoryId)) {
            throw EcomException.unprocessable("Category " + categoryId + " does not exist");
        }
    }

    /** Concurrent creates of the same SKU pass the pre-check; the primary key is the final arbiter. */
    private void flushOrConflict() {
        try {
            em.flush();
        } catch (PersistenceException e) {
            if (hasCause(e, org.hibernate.exception.ConstraintViolationException.class)) {
                throw EcomException.conflict("SKU or image already exists");
            }
            throw e;
        }
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> skus(List<VariantSpec> specs) {
        return specs.stream().map(VariantSpec::sku).toList();
    }

    private static EcomException notFound(UUID id) {
        return EcomException.notFound("Product " + id + " not found");
    }
}
