package com.ecom.catalog.grpc;

import static com.ecom.catalog.domain.ProductRulesTest.variant;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.catalog.domain.Category;
import com.ecom.catalog.domain.Product;
import com.ecom.catalog.domain.ProductDetails;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogProtoMapperTest {

    private final UUID category = UUID.randomUUID();
    private final Instant now = Instant.now();

    @Test
    void mapsVariantWithProductFieldsAndMoney() {
        Product p = Product.create(new ProductDetails("Shirt", null, category, null, true),
                List.of(variant("A-1", "19.99", "EUR")), now);

        var item = CatalogProtoMapper.item(p.getVariants().getFirst());

        assertThat(item.getSku()).isEqualTo("A-1");
        assertThat(item.getProductId()).isEqualTo(p.getId().toString());
        assertThat(item.getName()).isEqualTo("Shirt");
        assertThat(item.getVariantName()).isEqualTo("M");
        assertThat(item.getCategoryId()).isEqualTo(category.toString());
        assertThat(item.getActive()).isTrue();
        assertThat(item.getPrice().getCurrencyCode()).isEqualTo("EUR");
        assertThat(item.getPrice().getUnits()).isEqualTo(19);
        assertThat(item.getPrice().getNanos()).isEqualTo(990_000_000);
    }

    @Test
    void deletedOrRetiredVariantsAreInactive() {
        Product p = Product.create(new ProductDetails("Shirt", null, category, null, true),
                List.of(variant("A-1", "1", "EUR"), variant("B-2", "1", "EUR")), now);
        p.revise(new ProductDetails("Shirt", null, category, null, true), List.of(variant("A-1", "1", "EUR")), now);

        assertThat(CatalogProtoMapper.item(p.getVariants().get(1)).getActive()).isFalse();
        p.delete(now);
        assertThat(CatalogProtoMapper.item(p.getVariants().getFirst()).getActive()).isFalse();
    }

    @Test
    void rootCategoryHasEmptyParent() {
        var c = CatalogProtoMapper.category(Category.create(" Shoes ", null, now));
        assertThat(c.getName()).isEqualTo("Shoes");
        assertThat(c.getParentId()).isEmpty();
    }
}
