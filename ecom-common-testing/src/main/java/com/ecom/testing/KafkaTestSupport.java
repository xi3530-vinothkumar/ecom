package com.ecom.testing;

import com.ecom.common.events.EventEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.ObjectMapper;

/** Raw Kafka helpers for tests: publish envelopes as a foreign producer, and await envelopes. */
public final class KafkaTestSupport {

    public static void send(String bootstrap, ObjectMapper mapper, String topic, String key, EventEnvelope env) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (var producer = new KafkaProducer<String, String>(p)) {
            producer.send(new ProducerRecord<>(topic, key, mapper.writeValueAsString(env))).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
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
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<EventEnvelope> matched = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(p)) {
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
