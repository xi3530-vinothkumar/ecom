package com.ecom.catalog.application;

import static com.ecom.catalog.domain.ProductRulesTest.variant;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.catalog.domain.Product;
import com.ecom.catalog.domain.ProductDetails;
import com.ecom.common.events.Money;
import com.ecom.common.events.payload.ProductChangedPayload;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductEventMapperTest {

    private final UUID category = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void changedCarriesFullStateAndOnlyActiveVariants() {
        Product p = Product.create(new ProductDetails("Shirt", "Cotton", category, "Acme", true),
                List.of(variant("A-1", "10.5", "EUR"), variant("B-2", "20", "EUR")), now);
        p.revise(new ProductDetails("Shirt", "Cotton", category, "Acme", true),
                List.of(variant("A-1", "12.3456", "EUR")), now);

        ProductChangedPayload payload = ProductEventMapper.changed(p);

        assertThat(payload.productId()).isEqualTo(p.getId().toString());
        assertThat(payload.categoryId()).isEqualTo(category.toString());
        assertThat(payload.version()).isEqualTo(2);
        assertThat(payload.active()).isTrue();
        assertThat(payload.variants()).containsExactly(
                new ProductChangedPayload.Variant("A-1", "M", new Money("EUR", 12, 345_600_000)));
    }

    @Test
    void deletedListsEverySkuWithBumpedVersion() {
        Product p = Product.create(new ProductDetails("Shirt", null, category, null, true),
                List.of(variant("A-1", "1", "EUR"), variant("B-2", "2", "EUR")), now);
        p.revise(new ProductDetails("Shirt", null, category, null, true), List.of(variant("A-1", "1", "EUR")), now);
        p.delete(now);

        var payload = ProductEventMapper.deleted(p);

        assertThat(payload.skus()).containsExactly("A-1", "B-2");
        assertThat(payload.version()).isEqualTo(3);
        assertThat(ProductEventMapper.changed(p).active()).isFalse();
    }
}
