package com.ecom.catalog.web;

import static com.ecom.catalog.domain.ProductRulesTest.variant;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.catalog.application.CatalogImagesProperties;
import com.ecom.catalog.domain.Product;
import com.ecom.catalog.domain.ProductDetails;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductWebMapperTest {

    private final ProductWebMapper mapper = new ProductWebMapper(
            new CatalogImagesProperties(true, "bucket", "eu-west-1", "https://cdn.example.com/", Duration.ofMinutes(5)));

    @Test
    void formatsPricesAsPlainDecimals() {
        assertThat(ProductWebMapper.price(new BigDecimal("20.0000"), "EUR").amount()).isEqualTo("20.00");
        assertThat(ProductWebMapper.price(new BigDecimal("19.9950"), "EUR").amount()).isEqualTo("19.995");
        assertThat(ProductWebMapper.price(new BigDecimal("1000"), "EUR").amount()).isEqualTo("1000.00");
    }

    @Test
    void summaryUsesCheapestActiveVariantAndFirstImage() {
        Product p = Product.create(new ProductDetails("Shirt", null, UUID.randomUUID(), "Acme", true),
                List.of(variant("A-1", "30", "EUR"), variant("B-2", "12.5", "EUR")), Instant.now());
        p.addImage("products/p/b.jpg", 1, Instant.now());
        p.addImage("products/p/a.jpg", 0, Instant.now());

        var summary = mapper.summary(p);

        assertThat(summary.fromPrice().amount()).isEqualTo("12.50");
        assertThat(summary.imageUrl()).isEqualTo("https://cdn.example.com/products/p/a.jpg");
    }

    @Test
    void fallsBackToS3UrlWithoutCdn() {
        var props = new CatalogImagesProperties(true, "bkt", "eu-central-1", "", Duration.ofMinutes(5));
        assertThat(props.publicUrl("k.jpg")).isEqualTo("https://bkt.s3.eu-central-1.amazonaws.com/k.jpg");
        assertThat(new CatalogImagesProperties(false, null, "eu-west-1", null, Duration.ZERO).publicUrl("k")).isNull();
    }
}
