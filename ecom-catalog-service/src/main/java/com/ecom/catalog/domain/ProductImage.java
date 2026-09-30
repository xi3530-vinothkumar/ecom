package com.ecom.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_images")
public class ProductImage {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "s3_key", nullable = false, updatable = false)
    private String s3Key;

    @Column(nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductImage() {}

    ProductImage(Product product, String s3Key, int position, Instant now) {
        this.id = UUID.randomUUID();
        this.product = product;
        this.s3Key = s3Key;
        this.position = position;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getS3Key() {
        return s3Key;
    }

    public int getPosition() {
        return position;
    }
}
