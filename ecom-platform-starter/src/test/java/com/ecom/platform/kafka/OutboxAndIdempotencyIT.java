package com.ecom.platform.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecom.common.events.EventEnvelope;
import com.ecom.common.events.EventTopics;
import com.ecom.common.events.payload.UserRegisteredPayload;
import com.ecom.platform.TestApp;
import com.ecom.testing.Containers;
import com.ecom.testing.KafkaTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxAndIdempotencyIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Containers.registerPostgres(r);
        Containers.registerKafka(r);
    }

    @Autowired OutboxWriter outbox;
    @Autowired IdempotentConsumer consumer;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void committedEventIsRelayedToKafka() {
        String userId = UUID.randomUUID().toString();
        tx.executeWithoutResult(s -> outbox.publish(EventTopics.USER_REGISTERED, userId, new UserRegisteredPayload(userId)));

        List<EventEnvelope> got = KafkaTestSupport.await(
                Containers.kafka().getBootstrapServers(), mapper, EventTopics.USER_REGISTERED,
                e -> userId.equals(e.payload().path("userId").asText()), Duration.ofSeconds(20));

        assertThat(got).hasSize(1);
        assertThat(got.getFirst().producerService()).isEqualTo("platform-test");
        assertThat(got.getFirst().eventType()).isEqualTo(EventTopics.USER_REGISTERED);
    }

    @Test
    void rolledBackTransactionWritesNoOutboxRow() {
        String userId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    outbox.publish(EventTopics.USER_REGISTERED, userId, new UserRegisteredPayload(userId));
                    throw new IllegalStateException("business failure");
                }))
                .isInstanceOf(IllegalStateException.class);

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE message_key = ?", Integer.class, userId);
        assertThat(rows).isZero();
    }

    @Test
    void publishOutsideTransactionIsRejected() {
        assertThatThrownBy(() -> outbox.publish(EventTopics.USER_REGISTERED, "k", new UserRegisteredPayload("u")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateDeliveryIsHandledOnce() throws Exception {
        var env = EventEnvelope.create(EventTopics.USER_REGISTERED, 1, UUID.randomUUID().toString(), "test",
                new UserRegisteredPayload("u-dup"), mapper);
        var record = new ConsumerRecord<>(EventTopics.USER_REGISTERED, 0, 0L, "u-dup", mapper.writeValueAsString(env));
        AtomicInteger calls = new AtomicInteger();

        consumer.handle(record, "test-cg", e -> calls.incrementAndGet());
        consumer.handle(record, "test-cg", e -> calls.incrementAndGet());
        consumer.handle(record, "other-cg", e -> calls.incrementAndGet());

        assertThat(calls).hasValue(2);
    }

    @Test
    void failedHandlerRollsBackDedupMarkerSoRetryReprocesses() throws Exception {
        var env = EventEnvelope.create(EventTopics.USER_REGISTERED, 1, UUID.randomUUID().toString(), "test",
                new UserRegisteredPayload("u-retry"), mapper);
        var record = new ConsumerRecord<>(EventTopics.USER_REGISTERED, 0, 0L, "u-retry", mapper.writeValueAsString(env));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> consumer.handle(record, "retry-cg", e -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("transient");
                }))
                .isInstanceOf(IllegalStateException.class);
        consumer.handle(record, "retry-cg", e -> calls.incrementAndGet());

        assertThat(calls).hasValue(2);
    }

    @Test
    void consumerStateChangeAndEmittedEventCommitAtomically() throws Exception {
        String userId = "u-atomic-" + UUID.randomUUID();
        var env = EventEnvelope.create(EventTopics.USER_REGISTERED, 1, UUID.randomUUID().toString(), "test",
                new UserRegisteredPayload(userId), mapper);
        var record = new ConsumerRecord<>(EventTopics.USER_REGISTERED, 0, 0L, userId, mapper.writeValueAsString(env));

        assertThatThrownBy(() -> consumer.handle(record, "atomic-cg", e -> {
                    outbox.publish(EventTopics.USER_REGISTERED, userId, new UserRegisteredPayload(userId));
                    throw new IllegalStateException("fail after emit");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE message_key = ?", Integer.class, userId))
                .isZero();
    }
}
