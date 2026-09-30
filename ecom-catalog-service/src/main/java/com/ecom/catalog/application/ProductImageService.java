package com.ecom.catalog.application;

import com.ecom.catalog.domain.Product;
import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.EcomException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Two-step image upload: (1) hand out a pre-signed S3 PUT URL for a server-chosen key, (2) the admin
 * confirms after uploading and the key is registered on the product (after verifying the object exists).
 */
@Service
public class ProductImageService {

    static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/avif", "avif");

    private static final Pattern KEY_TAIL = Pattern.compile("^[0-9a-f-]{36}\\.(jpg|png|webp|avif)$");

    private final Optional<ImageStorage> storage;
    private final CatalogImagesProperties props;
    private final ProductAdminService admin;

    public ProductImageService(
            ObjectProvider<ImageStorage> storage, CatalogImagesProperties props, ProductAdminService admin) {
        this.storage = Optional.ofNullable(storage.getIfAvailable());
        this.props = props;
        this.admin = admin;
    }

    public UploadTicket requestUpload(UUID productId, String contentType) {
        CallContext.requireRole(CallContext.ROLE_ADMIN);
        ImageStorage store = requireStorage();
        String ext = EXTENSIONS.get(contentType == null ? "" : contentType.toLowerCase());
        if (ext == null) {
            throw EcomException.validation("Unsupported image content type '" + contentType
                    + "'; allowed: " + EXTENSIONS.keySet().stream().sorted().toList());
        }
        admin.requireLive(productId);
        String key = keyPrefix(productId) + UUID.randomUUID() + "." + ext;
        ImageStorage.PresignedUpload upload = store.presignUpload(key, contentType.toLowerCase(), props.uploadUrlTtl());
        return new UploadTicket(key, upload);
    }

    public Product confirm(UUID productId, String s3Key, Integer position) {
        CallContext.requireRole(CallContext.ROLE_ADMIN);
        ImageStorage store = requireStorage();
        String prefix = keyPrefix(productId);
        if (s3Key == null || !s3Key.startsWith(prefix) || !KEY_TAIL.matcher(s3Key.substring(prefix.length())).matches()) {
            throw EcomException.validation("s3Key must be a key issued by upload-url for product " + productId);
        }
        if (position != null && position < 0) {
            throw EcomException.validation("position must be >= 0");
        }
        if (!store.exists(s3Key)) {
            throw EcomException.unprocessable("Image " + s3Key + " has not been uploaded yet");
        }
        return admin.addImage(productId, s3Key, position);
    }

    static String keyPrefix(UUID productId) {
        return "products/" + productId + "/";
    }

    private ImageStorage requireStorage() {
        return storage.orElseThrow(() -> EcomException.unprocessable(
                "Product image uploads are disabled (set IMAGES_ENABLED=true and IMAGES_BUCKET to enable)"));
    }

    public record UploadTicket(String s3Key, ImageStorage.PresignedUpload upload) {}
}
