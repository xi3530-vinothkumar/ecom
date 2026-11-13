package com.ecom.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.catalog.application.CatalogImagesProperties;
import com.ecom.catalog.application.ImageStorage;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Spring Cloud AWS S3 wiring on this Boot line; presigning is local, so no AWS access is needed. */
class S3ImagesConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CatalogImagesProperties.class)
    static class Props {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration.class,
                    io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration.class,
                    io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration.class,
                    io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration.class))
            .withUserConfiguration(Props.class, CatalogConfiguration.class)
            .withPropertyValues(
                    "spring.cloud.aws.region.static=eu-west-1",
                    "spring.cloud.aws.credentials.access-key=AKIDEXAMPLE",
                    "spring.cloud.aws.credentials.secret-key=secret");

    @Test
    void imagesEnabledWiresS3StorageOnSpringCloudAwsClients() {
        runner.withPropertyValues("ecom.catalog.images.enabled=true", "ecom.catalog.images.bucket=ecom-images")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(S3Client.class).hasSingleBean(S3Presigner.class);
                    var upload = ctx.getBean(ImageStorage.class)
                            .presignUpload("products/p1/abc.png", "image/png", Duration.ofMinutes(10));
                    assertThat(upload.url().getHost()).isEqualTo("ecom-images.s3.eu-west-1.amazonaws.com");
                    assertThat(upload.requiredHeaders()).containsEntry("content-type", "image/png");
                });
    }

    @Test
    void imagesDisabledHasNoS3Storage() {
        runner.withPropertyValues("ecom.catalog.images.enabled=false", "spring.cloud.aws.s3.enabled=false")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(S3ImageStorage.class).doesNotHaveBean(S3Client.class));
    }
}
