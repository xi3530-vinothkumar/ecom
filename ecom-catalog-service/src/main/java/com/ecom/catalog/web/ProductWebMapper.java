package com.ecom.catalog.web;

import com.ecom.catalog.application.CatalogImagesProperties;
import com.ecom.catalog.domain.Category;
import com.ecom.catalog.domain.Product;
import com.ecom.catalog.domain.ProductImage;
import com.ecom.catalog.domain.ProductVariant;
import com.ecom.catalog.web.Dtos.AdminProductResponse;
import com.ecom.catalog.web.Dtos.AdminVariantResponse;
import com.ecom.catalog.web.Dtos.CategoryResponse;
import com.ecom.catalog.web.Dtos.ImageResponse;
import com.ecom.catalog.web.Dtos.PriceResponse;
import com.ecom.catalog.web.Dtos.ProductDetailResponse;
import com.ecom.catalog.web.Dtos.ProductSummaryResponse;
import com.ecom.catalog.web.Dtos.VariantResponse;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ProductWebMapper {

    private final CatalogImagesProperties images;

    public ProductWebMapper(CatalogImagesProperties images) {
        this.images = images;
    }

    public ProductSummaryResponse summary(Product p) {
        PriceResponse from = p.activeVariants().stream()
                .min(Comparator.comparing(ProductVariant::getPriceAmount))
                .map(v -> price(v.getPriceAmount(), v.getPriceCurrency()))
                .orElse(null);
        String imageUrl = p.getImages().isEmpty() ? null : images.publicUrl(p.getImages().getFirst().getS3Key());
        return new ProductSummaryResponse(p.getId(), p.getName(), p.getBrand(), p.getCategoryId(), from, imageUrl);
    }

    public ProductDetailResponse detail(Product p) {
        return new ProductDetailResponse(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getBrand(),
                p.getCategoryId(),
                p.activeVariants().stream()
                        .map(v -> new VariantResponse(v.getSku(), v.getVariantName(),
                                price(v.getPriceAmount(), v.getPriceCurrency())))
                        .toList(),
                images(p));
    }

    public AdminProductResponse admin(Product p) {
        return new AdminProductResponse(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getBrand(),
                p.getCategoryId(),
                p.isActive(),
                p.getVersion(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                p.getVariants().stream()
                        .map(v -> new AdminVariantResponse(v.getSku(), v.getVariantName(),
                                price(v.getPriceAmount(), v.getPriceCurrency()), v.isActive()))
                        .toList(),
                images(p));
    }

    public CategoryResponse category(Category c) {
        return new CategoryResponse(c.getId(), c.getName(), c.getParentId());
    }

    private List<ImageResponse> images(Product p) {
        return p.getImages().stream()
                .map(i -> image(i))
                .toList();
    }

    private ImageResponse image(ProductImage i) {
        return new ImageResponse(i.getId(), i.getS3Key(), images.publicUrl(i.getS3Key()), i.getPosition());
    }

    /** Plain decimal string with at least 2 fraction digits ("20.00", "19.9950"). */
    static PriceResponse price(BigDecimal amount, String currency) {
        BigDecimal n = amount.stripTrailingZeros();
        if (n.scale() < 2) {
            n = n.setScale(2);
        }
        return new PriceResponse(n.toPlainString(), currency);
    }
}
