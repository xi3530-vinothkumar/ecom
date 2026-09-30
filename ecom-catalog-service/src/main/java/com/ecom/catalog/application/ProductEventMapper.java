package com.ecom.catalog.application;

import com.ecom.catalog.domain.Product;
import com.ecom.catalog.domain.ProductVariant;
import com.ecom.common.events.payload.ProductChangedPayload;
import com.ecom.common.events.payload.ProductDeletedPayload;
import java.util.UUID;

/** Product aggregate -> event payloads (full state, event-carried state transfer). */
public final class ProductEventMapper {

    /** Only active variants are published; consumers drop SKUs that disappear from the list. */
    public static ProductChangedPayload changed(Product p) {
        return new ProductChangedPayload(
                p.getId().toString(),
                p.getName(),
                p.getDescription(),
                idOrNull(p.getCategoryId()),
                p.getBrand(),
                p.isActive(),
                p.getVersion(),
                p.activeVariants().stream()
                        .map(v -> new ProductChangedPayload.Variant(v.getSku(), v.getVariantName(), v.price()))
                        .toList());
    }

    /** Lists every SKU the product ever had, so consumers can drop all of them. */
    public static ProductDeletedPayload deleted(Product p) {
        return new ProductDeletedPayload(
                p.getId().toString(),
                p.getVariants().stream().map(ProductVariant::getSku).toList(),
                p.getVersion());
    }

    private static String idOrNull(UUID id) {
        return id == null ? null : id.toString();
    }

    private ProductEventMapper() {}
}
