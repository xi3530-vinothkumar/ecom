package com.ecom.search.infrastructure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.message.BasicHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Plain HTTP locally / in Compose; in AWS envs HTTPS to Elastic Cloud over PrivateLink, authenticated
 * with an API key ({@code ecom.elasticsearch.api-key}, synced from Secrets Manager).
 */
@Configuration(proxyBeanMethods = false)
public class ElasticsearchConfiguration {

    @Bean(destroyMethod = "close")
    Rest5Client elasticsearchRestClient(
            @Value("${ecom.elasticsearch.url}") String url,
            @Value("${ecom.elasticsearch.api-key:}") String apiKey) {
        Rest5ClientBuilder builder = Rest5Client.builder(URI.create(url))
                .setConnectionConfigCallback(cc -> cc.setConnectTimeout(5, TimeUnit.SECONDS))
                .setRequestConfigCallback(rc -> rc.setResponseTimeout(30, TimeUnit.SECONDS));
        if (StringUtils.hasText(apiKey)) {
            builder.setDefaultHeaders(new Header[] {new BasicHeader(HttpHeaders.AUTHORIZATION, "ApiKey " + apiKey)});
        }
        return builder.build();
    }

    @Bean
    ElasticsearchTransport elasticsearchTransport(Rest5Client restClient, ObjectMapper mapper) {
        return new Rest5ClientTransport(restClient, new JacksonJsonpMapper(mapper.copy()));
    }

    @Bean
    ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }

    /** Readiness contributor (see management.endpoint.health.group.readiness). */
    @Bean("elasticsearch")
    HealthIndicator elasticsearchHealthIndicator(ElasticsearchClient client) {
        return () -> {
            try {
                return client.ping().value() ? Health.up().build() : Health.down().withDetail("ping", false).build();
            } catch (IOException | RuntimeException e) {
                return Health.down(e).build();
            }
        };
    }
}
