package com.ecom.catalog.infrastructure;

import com.ecom.catalog.application.ImageStorage;
import com.ecom.platform.core.EcomException;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

public class S3ImageStorage implements ImageStorage {

    private final S3Presigner presigner;
    private final S3Client s3;
    private final String bucket;

    public S3ImageStorage(S3Presigner presigner, S3Client s3, String bucket) {
        this.presigner = presigner;
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType, Duration ttl) {
        PutObjectPresignRequest request = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build())
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(request);
        Map<String, String> headers = presigned.signedHeaders().entrySet().stream()
                .filter(e -> !e.getKey().equalsIgnoreCase("host"))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> String.join(",", e.getValue())));
        return new PresignedUpload(presigned.url(), headers, presigned.expiration());
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException _) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw EcomException.unavailable("Image storage unavailable", e);
        } catch (SdkException e) {
            throw EcomException.unavailable("Image storage unavailable", e);
        }
    }
}
