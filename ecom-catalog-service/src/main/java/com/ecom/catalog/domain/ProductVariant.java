package com.ecom.catalog.domain;

import com.ecom.common.events.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "product_variants")
public class ProductVariant {

    @Id
    private String sku;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "variant_name", nullable = false)
    private String variantName;

    @Column(name = "price_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal priceAmount;

    @Column(name = "price_currency", nullable = false, columnDefinition = "bpchar(3)")
    private String priceCurrency;

    @Column(nullable = false)
    private boolean active;

    protected ProductVariant() {}

    ProductVariant(Product product, VariantSpec spec) {
        this.sku = spec.sku();
        this.product = product;
        apply(spec);
    }

    void apply(VariantSpec spec) {
        this.variantName = spec.variantName().strip();
        this.priceAmount = spec.priceAmount();
        this.priceCurrency = spec.priceCurrency();
        this.active = spec.active();
    }

    void deactivate() {
        this.active = false;
    }

    public String getSku() {
        return sku;
    }

    public Product getProduct() {
        return product;
    }

    public String getVariantName() {
        return variantName;
    }

    public BigDecimal getPriceAmount() {
        return priceAmount;
    }

    public String getPriceCurrency() {
        return priceCurrency;
    }

    public Money price() {
        return Money.of(priceAmount, priceCurrency);
    }

    public boolean isActive() {
        return active;
    }

    /** Sellable = variant active and its product active and not deleted. */
    public boolean isSellable() {
        return active && product.isActive() && !product.isDeleted();
    }
}
