package com.ecom.testing;

import com.ecom.common.events.EventEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

/** Kafka helpers for tests (Spring Kafka): publish envelopes as a foreign producer, and await envelopes. */
public final class KafkaTestSupport {

    public static void send(String bootstrap, ObjectMapper mapper, String topic, String key, EventEnvelope env) {
        var producers = new DefaultKafkaProducerFactory<String, String>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap), new StringSerializer(), new StringSerializer());
        try {
            new KafkaTemplate<>(producers).send(topic, key, mapper.writeValueAsString(env)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            producers.destroy();
        }
    }

    public static EventEnvelope sendPayload(
            String bootstrap, ObjectMapper mapper, String topic, String key, Object payload) {
        var env = EventEnvelope.create(topic, 1, UUID.randomUUID().toString(), "test", payload, mapper);
        send(bootstrap, mapper, topic, key, env);
        return env;
    }

    /** Consumes {@code topic} from the beginning until {@code match} is satisfied or timeout. */
    public static List<EventEnvelope> await(
            String bootstrap, ObjectMapper mapper, String topic, Predicate<EventEnvelope> match, Duration timeout) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var consumers = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer());
        List<EventEnvelope> matched = new ArrayList<>();
        try (var consumer = consumers.createConsumer()) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline) && matched.isEmpty()) {
                for (ConsumerRecord<String, String> rec : consumer.poll(Duration.ofMillis(250))) {
                    EventEnvelope env = EventEnvelope.parse(mapper, rec.value());
                    if (match.test(env)) {
                        matched.add(env);
                    }
                }
            }
        }
        return matched;
    }

    public static Map<String, Object> consumerProps(String bootstrap) {
        return Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    }

    private KafkaTestSupport() {}
}
