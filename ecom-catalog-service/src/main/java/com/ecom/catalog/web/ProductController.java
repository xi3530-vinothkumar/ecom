package com.ecom.catalog.web;

import com.ecom.catalog.application.CategoryService;
import com.ecom.catalog.application.ProductQueryService;
import com.ecom.catalog.web.Dtos.CategoryResponse;
import com.ecom.catalog.web.Dtos.PageResponse;
import com.ecom.catalog.web.Dtos.ProductDetailResponse;
import com.ecom.catalog.web.Dtos.ProductSummaryResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public browse API (active, non-deleted products only). */
@RestController
public class ProductController {

    private final ProductQueryService queries;
    private final CategoryService categories;
    private final ProductWebMapper mapper;

    public ProductController(ProductQueryService queries, CategoryService categories, ProductWebMapper mapper) {
        this.queries = queries;
        this.categories = categories;
        this.mapper = mapper;
    }

    @GetMapping("/products")
    public PageResponse<ProductSummaryResponse> list(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = queries.listVisible(categoryId, page, size);
        return new PageResponse<>(
                result.getContent().stream().map(mapper::summary).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @GetMapping("/products/{id}")
    public ProductDetailResponse get(@PathVariable UUID id) {
        return mapper.detail(queries.getVisible(id));
    }

    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return categories.list().stream().map(mapper::category).toList();
    }
}
