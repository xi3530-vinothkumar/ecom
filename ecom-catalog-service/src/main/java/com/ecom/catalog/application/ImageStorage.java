package com.ecom.catalog.application;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Object storage for product images (S3 in production). Present only when images are enabled. */
public interface ImageStorage {

    PresignedUpload presignUpload(String key, String contentType, Duration ttl);

    boolean exists(String key);

    /** A pre-signed PUT; the client must send exactly {@code requiredHeaders}. */
    record PresignedUpload(URL url, Map<String, String> requiredHeaders, Instant expiresAt) {}
}
