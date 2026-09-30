package com.ecom.catalog.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecom.platform.core.EcomException;
import com.ecom.platform.core.ErrorCode;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class ProductRulesTest {

    private static final ProductDetails DETAILS = new ProductDetails("Shirt", null, UUID.randomUUID(), "Acme", true);

    public static VariantSpec variant(String sku, String amount, String currency) {
        return new VariantSpec(sku, "M", new BigDecimal(amount), currency, true);
    }

    @Test
    void acceptsValidProduct() {
        assertThatCode(() -> ProductRules.validate(DETAILS,
                        List.of(variant("SKU-1", "19.99", "EUR"), variant("SKU-2", "0.0001", "EUR"))))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "eur", "GBP"})
    void rejectsNonEurCurrency(String currency) {
        assertValidation(List.of(variant("SKU-1", "10", currency)), "must be EUR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0000", "-1.50"})
    void rejectsNonPositivePrice(String amount) {
        assertValidation(List.of(variant("SKU-1", amount, "EUR")), "greater than 0");
    }

    @Test
    void rejectsMoreThanFourDecimals() {
        assertValidation(List.of(variant("SKU-1", "1.00001", "EUR")), "at most 4 decimals");
    }

    @Test
    void trailingZerosBeyondFourDecimalsAreFine() {
        assertThatCode(() -> ProductRules.validate(DETAILS, List.of(variant("SKU-1", "1.500000", "EUR"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPriceOverflowingColumn() {
        assertValidation(List.of(variant("SKU-1", "1234567890123456", "EUR")), "too large");
    }

    @Test
    void rejectsDuplicateSkus() {
        assertValidation(List.of(variant("SKU-1", "1", "EUR"), variant("SKU-1", "2", "EUR")), "Duplicate SKU");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "-leading", "has space", "ü-umlaut"})
    void rejectsMalformedSku(String sku) {
        assertValidation(List.of(variant(sku, "1", "EUR")), "Invalid SKU");
    }

    @Test
    void rejectsTooLongSku() {
        assertValidation(List.of(variant("A".repeat(65), "1", "EUR")), "Invalid SKU");
    }

    @Test
    void requiresAtLeastOneVariant() {
        assertValidation(Collections.emptyList(), "at least one variant");
    }

    @Test
    void capsVariantCount() {
        var many = IntStream.rangeClosed(0, ProductRules.MAX_VARIANTS)
                .mapToObj(i -> variant("SKU-" + i, "1", "EUR"))
                .toList();
        assertValidation(many, "at most");
    }

    @Test
    void requiresVariantName() {
        assertValidation(List.of(new VariantSpec("SKU-1", " ", BigDecimal.ONE, "EUR", true)), "variantName");
    }

    @Test
    void requiresName() {
        assertThatThrownBy(() -> ProductRules.validate(
                        new ProductDetails(" ", null, UUID.randomUUID(), null, true), List.of(variant("S", "1", "EUR"))))
                .isInstanceOf(EcomException.class)
                .hasMessageContaining("name");
    }

    private static void assertValidation(List<VariantSpec> variants, String message) {
        assertThatThrownBy(() -> ProductRules.validate(DETAILS, variants))
                .isInstanceOfSatisfying(EcomException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION))
                .hasMessageContaining(message);
    }
}
