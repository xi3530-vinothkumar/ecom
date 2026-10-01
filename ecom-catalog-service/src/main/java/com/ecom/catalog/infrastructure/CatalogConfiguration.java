package com.ecom.catalog.infrastructure;

import com.ecom.catalog.application.CatalogImagesProperties;
import com.ecom.catalog.application.ImageStorage;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
public class CatalogConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * S3 client and presigner come from Spring Cloud AWS ({@code spring.cloud.aws.*}); credentials from
     * the default chain (IRSA web identity on EKS, env/profile locally).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ecom.catalog.images", name = "enabled", havingValue = "true")
    static class S3ImagesConfiguration {

        @Bean
        @ConditionalOnMissingBean(ImageStorage.class)
        ImageStorage s3ImageStorage(CatalogImagesProperties props, S3Presigner presigner, S3Client s3) {
            if (props.bucket() == null || props.bucket().isBlank()) {
                throw new IllegalStateException("IMAGES_ENABLED=true requires IMAGES_BUCKET");
            }
            return new S3ImageStorage(presigner, s3, props.bucket());
        }
    }
}
