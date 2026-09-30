package com.ecom.platform.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Needs a {@link ProcessedEventStore}: the JDBC one from {@link OutboxAutoConfiguration}, or one the
 * service defines itself (e.g. Redis SETNX in Cart). Search relies on versioned upserts instead.
 */
@AutoConfiguration(after = OutboxAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnBean(ProcessedEventStore.class)
public class IdempotentConsumerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    IdempotentConsumer idempotentConsumer(
            ProcessedEventStore store, ObjectProvider<PlatformTransactionManager> txm, ObjectMapper mapper) {
        PlatformTransactionManager tm = txm.getIfAvailable();
        return new IdempotentConsumer(store, tm == null ? null : new TransactionTemplate(tm), mapper);
    }
}
