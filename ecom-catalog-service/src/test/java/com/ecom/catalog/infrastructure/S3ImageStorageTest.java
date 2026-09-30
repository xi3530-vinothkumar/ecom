package com.ecom.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Pre-signing is a local computation, so this runs offline against the real SDK. */
class S3ImageStorageTest {

    @Test
    void presignsPutForKeyWithContentTypeAndExpiry() {
        var creds = StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secret"));
        try (var presigner = S3Presigner.builder().region(Region.EU_WEST_1).credentialsProvider(creds).build();
                var s3 = S3Client.builder().region(Region.EU_WEST_1).credentialsProvider(creds).build()) {
            var storage = new S3ImageStorage(presigner, s3, "ecom-images");

            var upload = storage.presignUpload("products/p1/abc.png", "image/png", Duration.ofMinutes(10));

            assertThat(upload.url().getHost()).isEqualTo("ecom-images.s3.eu-west-1.amazonaws.com");
            assertThat(upload.url().getPath()).isEqualTo("/products/p1/abc.png");
            assertThat(upload.url().getQuery()).contains("X-Amz-Signature=", "X-Amz-Expires=600");
            assertThat(upload.requiredHeaders()).containsEntry("content-type", "image/png").doesNotContainKey("host");
            assertThat(upload.expiresAt()).isBetween(Instant.now().plusSeconds(590), Instant.now().plusSeconds(610));
        }
    }
}
