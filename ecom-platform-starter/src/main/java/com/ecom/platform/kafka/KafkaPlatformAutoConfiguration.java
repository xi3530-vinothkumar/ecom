package com.ecom.platform.kafka;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.KafkaTopicDefinitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@AutoConfiguration(after = {
    KafkaAutoConfiguration.class,
    DataSourceAutoConfiguration.class,
    TransactionAutoConfiguration.class
}, afterName = "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@ConditionalOnClass(KafkaTemplate.class)
@EnableConfigurationProperties(OutboxProperties.class)
public class KafkaPlatformAutoConfiguration {

    /**
     * Retry 1s / 5s / 15s, then dead-letter to {@code <topic>.dlt} on the same partition. Needs a
     * {@link KafkaTemplate}: spring-kafka can be on the classpath transitively (e.g. the Gateway) without
     * Boot's Kafka auto-configuration creating one.
     */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(
                template, (rec, ex) -> new TopicPartition(EventTopics.dlt(rec.topic()), rec.partition()));
        var backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(5);
        backOff.setMaxInterval(15_000);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        // malformed envelopes will never succeed: straight to DLT
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }

    /** Local/Compose only: create all topics + DLTs from kafka-topics.yaml with RF=1. EKS: Terraform. */
    @Bean
    @ConditionalOnProperty(prefix = "ecom.kafka", name = "create-topics", havingValue = "true")
    KafkaAdmin.NewTopics ecomTopics(@Value("${ecom.kafka.replication-factor:1}") int rf) {
        var defs = KafkaTopicDefinitions.load();
        NewTopic[] topics = defs.topics().stream()
                .flatMap(t -> java.util.stream.Stream.of(t.name(), EventTopics.dlt(t.name()))
                        .map(n -> TopicBuilder.name(n).partitions(t.partitions()).replicas(rf).build()))
                .toArray(NewTopic[]::new);
        return new KafkaAdmin.NewTopics(topics);
    }
}
