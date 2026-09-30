package com.ecom.catalog.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * <pre>
 * ecom.catalog.images.enabled: ${IMAGES_ENABLED:false}
 * ecom.catalog.images.bucket: ${IMAGES_BUCKET:}
 * ecom.catalog.images.region: ${AWS_REGION:eu-west-1}
 * ecom.catalog.images.cdn-base-url: ${IMAGES_CDN_BASE_URL:}
 * </pre>
 */
@ConfigurationProperties("ecom.catalog.images")
public record CatalogImagesProperties(
        @DefaultValue("false") boolean enabled,
        String bucket,
        @DefaultValue("eu-west-1") String region,
        String cdnBaseUrl,
        @DefaultValue("10m") Duration uploadUrlTtl) {

    /** Public URL for a stored key: CDN if configured, else the bucket's virtual-hosted S3 URL. */
    public String publicUrl(String s3Key) {
        if (cdnBaseUrl != null && !cdnBaseUrl.isBlank()) {
            return stripTrailingSlash(cdnBaseUrl) + "/" + s3Key;
        }
        if (bucket != null && !bucket.isBlank()) {
            return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + s3Key;
        }
        return null;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
