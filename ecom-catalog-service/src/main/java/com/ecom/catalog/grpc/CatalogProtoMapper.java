package com.ecom.catalog.grpc;

import com.ecom.catalog.domain.Category;
import com.ecom.catalog.domain.ProductVariant;
import com.ecom.catalog.proto.v1.ProductItem;
import com.ecom.platform.core.MoneyMapper;

public final class CatalogProtoMapper {

    /** {@code active} = sellable now (variant + product active, product not deleted). */
    public static ProductItem item(ProductVariant v) {
        var p = v.getProduct();
        return ProductItem.newBuilder()
                .setSku(v.getSku())
                .setProductId(p.getId().toString())
                .setName(p.getName())
                .setVariantName(v.getVariantName())
                .setPrice(MoneyMapper.toProto(v.price()))
                .setActive(v.isSellable())
                .setCategoryId(p.getCategoryId().toString())
                .build();
    }

    public static com.ecom.catalog.proto.v1.Category category(Category c) {
        var b = com.ecom.catalog.proto.v1.Category.newBuilder()
                .setCategoryId(c.getId().toString())
                .setName(c.getName());
        if (c.getParentId() != null) {
            b.setParentId(c.getParentId().toString());
        }
        return b.build();
    }

    private CatalogProtoMapper() {}
}
