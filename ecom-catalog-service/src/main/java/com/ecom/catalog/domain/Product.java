package com.ecom.catalog.domain;

import com.ecom.platform.core.EcomException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Product aggregate (product + variants + images). Every mutation bumps {@code version}; callers hold a
 * row lock (SELECT ... FOR UPDATE) so versions are strictly increasing and events are emitted in order.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    private String brand;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @OneToMany(mappedBy = "product", cascade = CascadeType.PERSIST)
    @OrderBy("sku")
    private List<ProductVariant> variants = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.PERSIST)
    @OrderBy("position, createdAt")
    private List<ProductImage> images = new ArrayList<>();

    protected Product() {}

    public static Product create(ProductDetails details, List<VariantSpec> variants, Instant now) {
        ProductRules.validate(details, variants);
        Product p = new Product();
        p.id = UUID.randomUUID();
        p.createdAt = now;
        p.version = 0;
        p.applyDetails(details);
        variants.forEach(spec -> p.variants.add(new ProductVariant(p, spec)));
        p.touch(now);
        return p;
    }

    /**
     * Replaces product details and the variant set. Variants present in {@code desired} are created or
     * updated; owned variants missing from it are deactivated (SKUs are never deleted or reused).
     * The caller must have verified that none of the desired SKUs belongs to another product.
     */
    public void revise(ProductDetails details, List<VariantSpec> desired, Instant now) {
        requireNotDeleted();
        ProductRules.validate(details, desired);
        applyDetails(details);
        Map<String, ProductVariant> owned =
                variants.stream().collect(Collectors.toMap(ProductVariant::getSku, Function.identity()));
        Set<String> desiredSkus = desired.stream().map(VariantSpec::sku).collect(Collectors.toSet());
        for (VariantSpec spec : desired) {
            ProductVariant existing = owned.get(spec.sku());
            if (existing != null) {
                existing.apply(spec);
            } else {
                variants.add(new ProductVariant(this, spec));
            }
        }
        variants.stream().filter(v -> !desiredSkus.contains(v.getSku())).forEach(ProductVariant::deactivate);
        variants.sort(Comparator.comparing(ProductVariant::getSku));
        touch(now);
    }

    public void delete(Instant now) {
        requireNotDeleted();
        this.active = false;
        this.deletedAt = now;
        touch(now);
    }

    public ProductImage addImage(String s3Key, Integer position, Instant now) {
        requireNotDeleted();
        boolean duplicate = images.stream().anyMatch(i -> i.getS3Key().equals(s3Key));
        if (duplicate) {
            throw EcomException.conflict("Image " + s3Key + " is already registered");
        }
        int pos = position != null ? position : images.stream().mapToInt(ProductImage::getPosition).max().orElse(-1) + 1;
        ProductImage image = new ProductImage(this, s3Key, pos, now);
        images.add(image);
        images.sort(Comparator.comparingInt(ProductImage::getPosition));
        touch(now);
        return image;
    }

    private void applyDetails(ProductDetails d) {
        this.name = d.name().strip();
        this.description = blankToNull(d.description());
        this.categoryId = d.categoryId();
        this.brand = blankToNull(d.brand());
        this.active = d.active();
    }

    private void touch(Instant now) {
        this.version++;
        this.updatedAt = now;
    }

    private void requireNotDeleted() {
        if (isDeleted()) {
            throw EcomException.notFound("Product " + id + " not found");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    public List<ProductVariant> activeVariants() {
        return variants.stream().filter(ProductVariant::isActive).toList();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public String getBrand() {
        return brand;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<ProductVariant> getVariants() {
        return Collections.unmodifiableList(variants);
    }

    public List<ProductImage> getImages() {
        return Collections.unmodifiableList(images);
    }
}
