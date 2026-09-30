package com.ecom.catalog.application;

import com.ecom.catalog.domain.Product;
import com.ecom.catalog.domain.ProductVariant;
import com.ecom.catalog.infrastructure.ProductRepository;
import com.ecom.catalog.infrastructure.ProductVariantRepository;
import com.ecom.platform.core.EcomException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public and internal (gRPC) reads. */
@Service
@Transactional(readOnly = true)
public class ProductQueryService {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_BATCH = 500;

    private final ProductRepository products;
    private final ProductVariantRepository variants;

    public ProductQueryService(ProductRepository products, ProductVariantRepository variants) {
        this.products = products;
        this.variants = variants;
    }

    public Page<Product> listVisible(UUID categoryId, int page, int size) {
        if (page < 0) {
            throw EcomException.validation("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw EcomException.validation("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        Page<Product> result = categoryId == null
                ? products.findVisible(pageable)
                : products.findVisibleInCategory(categoryId, pageable);
        result.forEach(ProductQueryService::initialize);
        return result;
    }

    public Product getVisible(UUID id) {
        Product p = products.findById(id)
                .filter(x -> x.isActive() && !x.isDeleted())
                .orElseThrow(() -> EcomException.notFound("Product " + id + " not found"));
        initialize(p);
        return p;
    }

    /** Any SKU ever created resolves (inactive/deleted ones with sellable=false), so carts/orders can explain. */
    public ProductVariant getBySku(String sku) {
        if (sku == null || sku.isBlank()) {
            throw EcomException.validation("sku is required");
        }
        return variants.findWithProduct(sku).orElseThrow(() -> EcomException.notFound("SKU " + sku + " not found"));
    }

    /** Unknown SKUs are absent; result follows request order with duplicates removed. */
    public List<ProductVariant> getBySkus(Collection<String> skus) {
        var unique = new LinkedHashSet<>(skus);
        if (unique.size() > MAX_BATCH) {
            throw EcomException.validation("At most " + MAX_BATCH + " SKUs per batch");
        }
        if (unique.isEmpty()) {
            return List.of();
        }
        var bySku = new java.util.HashMap<String, ProductVariant>();
        variants.findAllWithProduct(unique).forEach(v -> bySku.put(v.getSku(), v));
        return unique.stream().map(bySku::get).filter(java.util.Objects::nonNull).toList();
    }

    private static void initialize(Product p) {
        p.getVariants().size();
        p.getImages().size();
    }
}
