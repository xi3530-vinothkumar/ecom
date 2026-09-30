package com.ecom.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "categories")
public class Category {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Category() {}

    private Category(UUID id, String name, UUID parentId, Instant now) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Category create(String name, UUID parentId, Instant now) {
        return new Category(UUID.randomUUID(), name.strip(), parentId, now);
    }

    public void rename(String name, UUID parentId, Instant now) {
        this.name = name.strip();
        this.parentId = parentId;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public UUID getParentId() {
        return parentId;
    }
}
