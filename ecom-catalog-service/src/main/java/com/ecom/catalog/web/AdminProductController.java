package com.ecom.catalog.web;

import com.ecom.catalog.application.ProductAdminService;
import com.ecom.catalog.application.ProductImageService;
import com.ecom.catalog.web.Dtos.AdminProductResponse;
import com.ecom.catalog.web.Dtos.ConfirmImageRequest;
import com.ecom.catalog.web.Dtos.ProductRequest;
import com.ecom.catalog.web.Dtos.UploadUrlRequest;
import com.ecom.catalog.web.Dtos.UploadUrlResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/products")
public class AdminProductController {

    private final ProductAdminService admin;
    private final ProductImageService images;
    private final ProductWebMapper mapper;

    public AdminProductController(ProductAdminService admin, ProductImageService images, ProductWebMapper mapper) {
        this.admin = admin;
        this.images = images;
        this.mapper = mapper;
    }

    @GetMapping("/{id}")
    public AdminProductResponse get(@PathVariable UUID id) {
        return mapper.admin(admin.get(id));
    }

    @PostMapping
    public ResponseEntity<AdminProductResponse> create(@Valid @RequestBody ProductRequest body) {
        var product = admin.create(body.toDetails(), body.toSpecs());
        return ResponseEntity.created(URI.create("/admin/products/" + product.getId())).body(mapper.admin(product));
    }

    @PutMapping("/{id}")
    public AdminProductResponse update(@PathVariable UUID id, @Valid @RequestBody ProductRequest body) {
        return mapper.admin(admin.update(id, body.version(), body.toDetails(), body.toSpecs()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        admin.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/images/upload-url")
    public UploadUrlResponse uploadUrl(@PathVariable UUID id, @Valid @RequestBody UploadUrlRequest body) {
        var ticket = images.requestUpload(id, body.contentType());
        return new UploadUrlResponse(ticket.s3Key(), ticket.upload().url(), "PUT",
                ticket.upload().requiredHeaders(), ticket.upload().expiresAt());
    }

    @PostMapping("/{id}/images")
    public ResponseEntity<AdminProductResponse> confirmImage(
            @PathVariable UUID id, @Valid @RequestBody ConfirmImageRequest body) {
        var product = images.confirm(id, body.s3Key(), body.position());
        return ResponseEntity.status(201).body(mapper.admin(product));
    }
}
