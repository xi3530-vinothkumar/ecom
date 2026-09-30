package com.ecom.catalog.web;

import com.ecom.catalog.application.CategoryService;
import com.ecom.catalog.web.Dtos.CategoryRequest;
import com.ecom.catalog.web.Dtos.CategoryResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/categories")
public class AdminCategoryController {

    private final CategoryService categories;
    private final ProductWebMapper mapper;

    public AdminCategoryController(CategoryService categories, ProductWebMapper mapper) {
        this.categories = categories;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest body) {
        var c = categories.create(body.name(), body.parentId());
        return ResponseEntity.created(URI.create("/categories/" + c.getId())).body(mapper.category(c));
    }

    @PutMapping("/{id}")
    public CategoryResponse update(@PathVariable UUID id, @Valid @RequestBody CategoryRequest body) {
        return mapper.category(categories.update(id, body.name(), body.parentId()));
    }
}
