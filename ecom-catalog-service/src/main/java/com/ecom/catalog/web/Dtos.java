package com.ecom.catalog.web;

import com.ecom.catalog.domain.ProductDetails;
import com.ecom.catalog.domain.VariantSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URL;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response bodies of the catalog REST API. */
public final class Dtos {

    public record PriceRequest(@NotNull BigDecimal amount, @NotBlank String currency) {}

    public record PriceResponse(String amount, String currency) {}

    public record VariantRequest(
            @NotBlank @Size(max = 64) String sku,
            @NotBlank @Size(max = 200) String variantName,
            @NotNull @Valid PriceRequest price,
            Boolean active) {

        VariantSpec toSpec() {
            return new VariantSpec(sku.strip(), variantName, price.amount(), price.currency().strip(),
                    active == null || active);
        }
    }

    /** {@code version} on update = optimistic concurrency check (omit to overwrite unconditionally). */
    public record ProductRequest(
            @NotBlank @Size(max = 300) String name,
            @Size(max = 10_000) String description,
            @NotNull UUID categoryId,
            @Size(max = 200) String brand,
            Boolean active,
            Long version,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid VariantRequest> variants) {

        ProductDetails toDetails() {
            return new ProductDetails(name, description, categoryId, brand, active == null || active);
        }

        List<VariantSpec> toSpecs() {
            return variants.stream().map(VariantRequest::toSpec).toList();
        }
    }

    public record CategoryRequest(@NotBlank @Size(max = 200) String name, UUID parentId) {}

    public record CategoryResponse(UUID id, String name, UUID parentId) {}

    public record ImageResponse(UUID id, String s3Key, String url, int position) {}

    public record VariantResponse(String sku, String variantName, PriceResponse price) {}

    public record AdminVariantResponse(String sku, String variantName, PriceResponse price, boolean active) {}

    public record ProductSummaryResponse(
            UUID id, String name, String brand, UUID categoryId, PriceResponse fromPrice, String imageUrl) {}

    public record ProductDetailResponse(
            UUID id,
            String name,
            String description,
            String brand,
            UUID categoryId,
            List<VariantResponse> variants,
            List<ImageResponse> images) {}

    public record AdminProductResponse(
            UUID id,
            String name,
            String description,
            String brand,
            UUID categoryId,
            boolean active,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<AdminVariantResponse> variants,
            List<ImageResponse> images) {}

    public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {}

    public record UploadUrlRequest(@NotBlank String contentType) {}

    public record UploadUrlResponse(String s3Key, URL uploadUrl, String method, Map<String, String> headers,
            Instant expiresAt) {}

    public record ConfirmImageRequest(@NotBlank @Size(max = 1024) String s3Key, @PositiveOrZero Integer position) {}

    private Dtos() {}
}
