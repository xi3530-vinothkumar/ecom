package com.ecom.platform.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Outbox writer/relay + JDBC dedup store, for services with a relational DB and Kafka. */
@AutoConfiguration(after = {
    KafkaAutoConfiguration.class,
    DataSourceAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    TransactionAutoConfiguration.class
})
@ConditionalOnClass({KafkaTemplate.class, JdbcTemplate.class})
@ConditionalOnBean(DataSource.class)
@EnableScheduling
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxWriter outboxWriter(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            @Value("${spring.application.name}") String app,
            ObjectProvider<Tracer> tracer) {
        return new OutboxWriter(jdbc, mapper, app, tracer);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ecom.outbox", name = "relay-enabled", matchIfMissing = true)
    OutboxRelay outboxRelay(
            JdbcTemplate jdbc,
            KafkaTemplate<String, String> kafka,
            PlatformTransactionManager txm,
            OutboxProperties props,
            MeterRegistry meters) {
        return new OutboxRelay(jdbc, kafka, new TransactionTemplate(txm), props, meters);
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessedEventStore processedEventStore(JdbcTemplate jdbc) {
        return new JdbcProcessedEventStore(jdbc);
    }
}
