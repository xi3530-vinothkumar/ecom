package com.ecom.catalog.domain;

import com.ecom.platform.core.EcomException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Business validation for admin product writes (shape validation lives on the request DTOs). */
public final class ProductRules {

    public static final String CURRENCY = "EUR";
    public static final int MAX_VARIANTS = 100;
    public static final Pattern SKU_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    // NUMERIC(19,4): at most 4 fraction digits and 15 integer digits
    private static final int MAX_SCALE = 4;
    private static final int MAX_INTEGER_DIGITS = 15;

    public static void validate(ProductDetails details, List<VariantSpec> variants) {
        if (details.name() == null || details.name().isBlank()) {
            throw EcomException.validation("Product name is required");
        }
        if (details.categoryId() == null) {
            throw EcomException.validation("categoryId is required");
        }
        if (variants == null || variants.isEmpty()) {
            throw EcomException.validation("A product needs at least one variant");
        }
        if (variants.size() > MAX_VARIANTS) {
            throw EcomException.validation("A product can have at most " + MAX_VARIANTS + " variants");
        }
        Set<String> skus = new HashSet<>();
        for (VariantSpec v : variants) {
            validateVariant(v);
            if (!skus.add(v.sku())) {
                throw EcomException.validation("Duplicate SKU in request: " + v.sku());
            }
        }
    }

    static void validateVariant(VariantSpec v) {
        if (v.sku() == null || !SKU_PATTERN.matcher(v.sku()).matches()) {
            throw EcomException.validation(
                    "Invalid SKU '" + v.sku() + "': 1-64 chars of letters, digits, '.', '_' or '-', starting alphanumeric");
        }
        if (v.variantName() == null || v.variantName().isBlank()) {
            throw EcomException.validation("variantName is required for SKU " + v.sku());
        }
        validatePrice(v.sku(), v.priceAmount(), v.priceCurrency());
    }

    static void validatePrice(String sku, BigDecimal amount, String currency) {
        if (!CURRENCY.equals(currency)) {
            throw EcomException.validation("Price currency must be " + CURRENCY + " (SKU " + sku + ")");
        }
        if (amount == null || amount.signum() <= 0) {
            throw EcomException.validation("Price must be greater than 0 (SKU " + sku + ")");
        }
        BigDecimal normalized = amount.stripTrailingZeros();
        if (normalized.scale() > MAX_SCALE) {
            throw EcomException.validation("Price supports at most " + MAX_SCALE + " decimals (SKU " + sku + ")");
        }
        if (normalized.precision() - normalized.scale() > MAX_INTEGER_DIGITS) {
            throw EcomException.validation("Price is too large (SKU " + sku + ")");
        }
    }

    private ProductRules() {}
}
