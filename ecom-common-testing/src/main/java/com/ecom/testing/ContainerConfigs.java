package com.ecom.testing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code @ServiceConnection} test configurations over the shared {@link Containers}: Spring Boot derives
 * datasource / Kafka / Redis / Elasticsearch connection details from the container. The beans are
 * JVM-wide singletons, so {@code destroyMethod = ""} keeps a closing context from stopping them.
 */
public final class ContainerConfigs {

    @TestConfiguration(proxyBeanMethods = false)
    public static class Postgres {
        @Bean(destroyMethod = "")
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return Containers.postgres();
        }
    }

    /** Also enables local topic creation (RF=1) from kafka-topics.yaml. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Kafka {
        @Bean(destroyMethod = "")
        @ServiceConnection
        KafkaContainer kafkaContainer() {
            return Containers.kafka();
        }

        @Bean
        DynamicPropertyRegistrar kafkaTopicCreation() {
            return r -> r.add("ecom.kafka.create-topics", () -> "true");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Redis {
        @Bean(destroyMethod = "")
        @ServiceConnection(name = "redis")
        GenericContainer<?> redisContainer() {
            return Containers.redis();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Elasticsearch {
        @Bean(destroyMethod = "")
        @ServiceConnection
        ElasticsearchContainer elasticsearchContainer() {
            return Containers.elasticsearch();
        }
    }

    private ContainerConfigs() {}
}
