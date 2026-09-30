package com.ecom.payment.infrastructure.psp;

import com.ecom.payment.application.PaymentProperties;
import com.ecom.payment.application.psp.PspClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class PspConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    StripeSignatureVerifier stripeSignatureVerifier(PaymentProperties props, Clock clock) {
        return new StripeSignatureVerifier(props.webhookSecret(), props.webhookTolerance(), clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ecom.payment", name = "psp", havingValue = "fake")
    FakePspClient fakePspClient() {
        return new FakePspClient();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ecom.payment", name = "psp", havingValue = "stripe")
    PspClient stripePspClient(PaymentProperties props, RestClient.Builder builder, ObjectMapper mapper) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException("PSP_API_KEY must be set when PSP_MODE=stripe");
        }
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build());
        factory.setReadTimeout(props.readTimeout());
        RestClient http = StripePspClient.configure(builder.clone(), props.baseUrl(), props.apiKey())
                .requestFactory(factory)
                .build();
        return new StripePspClient(http, mapper);
    }
}
