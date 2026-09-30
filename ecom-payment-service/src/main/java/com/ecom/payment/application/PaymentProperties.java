package com.ecom.payment.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code ecom.payment.*}: {@code psp} = fake|stripe (PSP_MODE), {@code api-key} (PSP_API_KEY),
 * {@code base-url} (PSP_BASE_URL), {@code webhook-secret} (PSP_WEBHOOK_SECRET).
 */
@Validated
@ConfigurationProperties("ecom.payment")
public record PaymentProperties(
        @NotNull PspMode psp,
        String apiKey,
        @NotBlank String baseUrl,
        @NotBlank String webhookSecret,
        @NotNull Duration webhookTolerance,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {

    public enum PspMode {
        FAKE,
        STRIPE
    }
}
