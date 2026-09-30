package com.ecom.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.EcomException;
import com.ecom.platform.core.ErrorCode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ProductImageServiceTest {

    private final UUID productId = UUID.randomUUID();
    private final ImageStorage storage = mock(ImageStorage.class);
    private final ProductAdminService admin = mock(ProductAdminService.class);
    private final CatalogImagesProperties props =
            new CatalogImagesProperties(true, "bucket", "eu-west-1", null, Duration.ofMinutes(7));

    @BeforeEach
    void admin() {
        CallContext.set(new CallContext("c", "admin-1", Set.of(CallContext.ROLE_ADMIN)));
    }

    @AfterEach
    void clear() {
        CallContext.clear();
    }

    private ProductImageService service(ImageStorage s) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ImageStorage> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(s);
        return new ProductImageService(provider, props, admin);
    }

    @Test
    void disabledStorageGivesClearUnprocessableError() {
        var svc = service(null);
        assertThatThrownBy(() -> svc.requestUpload(productId, "image/png"))
                .isInstanceOfSatisfying(EcomException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNPROCESSABLE))
                .hasMessageContaining("IMAGES_ENABLED");
        assertThatThrownBy(() -> svc.confirm(productId, "products/x", null))
                .isInstanceOfSatisfying(EcomException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNPROCESSABLE));
    }

    @Test
    void issuesKeyUnderProductPrefixWithConfiguredTtl() throws Exception {
        var upload = new ImageStorage.PresignedUpload(URI.create("https://s3/x").toURL(), Map.of(), Instant.now());
        when(storage.presignUpload(any(), eq("image/webp"), eq(Duration.ofMinutes(7)))).thenReturn(upload);

        var ticket = service(storage).requestUpload(productId, "IMAGE/WEBP");

        assertThat(ticket.s3Key()).startsWith("products/" + productId + "/").endsWith(".webp");
        verify(admin).requireLive(productId);
    }

    @Test
    void rejectsUnsupportedContentType() {
        assertThatThrownBy(() -> service(storage).requestUpload(productId, "application/pdf"))
                .isInstanceOfSatisfying(EcomException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION));
    }

    @Test
    void confirmRejectsForeignOrForgedKeys() {
        var svc = service(storage);
        String otherProduct = "products/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".png";
        String traversal = "products/" + productId + "/../secret.png";
        assertThatThrownBy(() -> svc.confirm(productId, otherProduct, null)).isInstanceOf(EcomException.class);
        assertThatThrownBy(() -> svc.confirm(productId, traversal, null)).isInstanceOf(EcomException.class);
        verify(admin, never()).addImage(any(), any(), any());
    }

    @Test
    void confirmRequiresUploadedObject() {
        String key = "products/" + productId + "/" + UUID.randomUUID() + ".png";
        when(storage.exists(key)).thenReturn(false);
        assertThatThrownBy(() -> service(storage).confirm(productId, key, null))
                .isInstanceOfSatisfying(EcomException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNPROCESSABLE));

        when(storage.exists(key)).thenReturn(true);
        service(storage).confirm(productId, key, 2);
        verify(admin).addImage(productId, key, 2);
    }

    @Test
    void nonAdminIsForbidden() {
        CallContext.set(new CallContext("c", "user-1", Set.of()));
        assertThatThrownBy(() -> service(storage).requestUpload(productId, "image/png"))
                .isInstanceOfSatisfying(EcomException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
