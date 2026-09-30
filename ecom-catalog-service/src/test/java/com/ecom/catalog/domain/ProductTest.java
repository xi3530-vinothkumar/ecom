package com.ecom.catalog.domain;

import static com.ecom.catalog.domain.ProductRulesTest.variant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecom.common.events.Money;
import com.ecom.platform.core.EcomException;
import com.ecom.platform.core.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private final UUID category = UUID.randomUUID();

    private Product newProduct() {
        return Product.create(new ProductDetails(" Shirt ", "", category, "Acme", true),
                List.of(variant("B-2", "20", "EUR"), variant("A-1", "10.50", "EUR")), T0);
    }

    @Test
    void createStartsAtVersionOneWithNormalizedFields() {
        Product p = newProduct();
        assertThat(p.getVersion()).isEqualTo(1);
        assertThat(p.getName()).isEqualTo("Shirt");
        assertThat(p.getDescription()).isNull();
        assertThat(p.getCreatedAt()).isEqualTo(T0);
        assertThat(p.activeVariants()).hasSize(2);
        assertThat(p.getVariants()).allMatch(ProductVariant::isSellable);
    }

    @Test
    void reviseBumpsVersionUpdatesPricesAddsAndDeactivatesVariants() {
        Product p = newProduct();
        p.revise(new ProductDetails("Shirt v2", "desc", category, "Acme", true),
                List.of(variant("A-1", "11.00", "EUR"), variant("C-3", "5", "EUR")), T1);

        assertThat(p.getVersion()).isEqualTo(2);
        assertThat(p.getUpdatedAt()).isEqualTo(T1);
        assertThat(p.getVariants()).extracting(ProductVariant::getSku).containsExactly("A-1", "B-2", "C-3");
        assertThat(p.activeVariants()).extracting(ProductVariant::getSku).containsExactly("A-1", "C-3");
        assertThat(p.activeVariants().getFirst().price()).isEqualTo(Money.of("11.00", "EUR"));
    }

    @Test
    void reAddingARetiredSkuReactivatesIt() {
        Product p = newProduct();
        p.revise(new ProductDetails("Shirt", null, category, null, true), List.of(variant("A-1", "10", "EUR")), T1);
        p.revise(new ProductDetails("Shirt", null, category, null, true),
                List.of(variant("A-1", "10", "EUR"), variant("B-2", "20", "EUR")), T1);
        assertThat(p.activeVariants()).hasSize(2);
        assertThat(p.getVersion()).isEqualTo(3);
    }

    @Test
    void deleteDeactivatesAndBumpsVersionOnce() {
        Product p = newProduct();
        p.delete(T1);
        assertThat(p.isDeleted()).isTrue();
        assertThat(p.isActive()).isFalse();
        assertThat(p.getVersion()).isEqualTo(2);
        assertThat(p.getVariants()).noneMatch(ProductVariant::isSellable);
        assertThatThrownBy(() -> p.delete(T1))
                .isInstanceOfSatisfying(EcomException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void inactiveProductMakesVariantsUnsellable() {
        Product p = Product.create(new ProductDetails("X", null, category, null, false),
                List.of(variant("A-1", "1", "EUR")), T0);
        assertThat(p.getVariants().getFirst().isActive()).isTrue();
        assertThat(p.getVariants().getFirst().isSellable()).isFalse();
    }

    @Test
    void imagesAppendAtNextPositionAndRejectDuplicates() {
        Product p = newProduct();
        p.addImage("products/x/1.jpg", null, T1);
        p.addImage("products/x/2.jpg", null, T1);
        p.addImage("products/x/0.jpg", 0, T1);
        assertThat(p.getImages()).extracting(ProductImage::getPosition).containsExactly(0, 0, 1);
        assertThat(p.getVersion()).isEqualTo(4);
        assertThatThrownBy(() -> p.addImage("products/x/1.jpg", null, T1))
                .isInstanceOfSatisfying(EcomException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void invalidRevisionLeavesStateUntouched() {
        Product p = newProduct();
        assertThatThrownBy(() -> p.revise(new ProductDetails("Shirt", null, category, null, true),
                        List.of(variant("A-1", "10", "USD")), T1))
                .isInstanceOf(EcomException.class);
        assertThat(p.getVersion()).isEqualTo(1);
        assertThat(p.activeVariants()).hasSize(2);
    }
}
