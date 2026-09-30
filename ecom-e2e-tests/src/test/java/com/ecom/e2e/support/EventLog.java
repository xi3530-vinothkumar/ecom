package com.ecom.e2e.support;

import com.ecom.common.events.EventEnvelope;
import com.ecom.common.events.EventTopics;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Background tap on every saga topic (from the beginning), so tests can count events per order and
 * re-publish a captured record verbatim (same eventId) to exercise duplicate delivery.
 */
public final class EventLog implements AutoCloseable {

    public record Rec(String topic, String key, String rawValue, EventEnvelope envelope) {
        public String orderId() {
            var n = envelope.payload() == null ? null : envelope.payload().get("orderId");
            return n == null ? null : n.asString();
        }
    }

    private final List<Rec> records = new CopyOnWriteArrayList<>();
    private final KafkaConsumer<String, String> consumer;
    private final KafkaProducer<String, String> producer;
    private final Thread poller;
    private volatile boolean running = true;

    EventLog(String bootstrap) {
        Properties c = new Properties();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        c.put(ConsumerConfig.GROUP_ID_CONFIG, "e2e-tap-" + UUID.randomUUID());
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        c.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
        c.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, "2000");
        c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumer = new KafkaConsumer<>(c);
        consumer.subscribe(EventTopics.ALL);

        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(p);

        poller = new Thread(this::run, "e2e-event-tap");
        poller.setDaemon(true);
        poller.start();
    }

    private void run() {
        try {
            while (running) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                    try {
                        records.add(new Rec(r.topic(), r.key(), r.value(), EventEnvelope.parse(Json.MAPPER, r.value())));
                    } catch (RuntimeException ignored) {
                        // not an envelope (e.g. a foreign test record); ignore
                    }
                }
            }
        } catch (WakeupException ignored) {
            // closing
        } finally {
            consumer.close(Duration.ofSeconds(2));
        }
    }

    public List<Rec> forOrder(String topic, String orderId) {
        return records.stream().filter(r -> r.topic().equals(topic) && orderId.equals(r.orderId())).toList();
    }

    public long count(String topic, String orderId) {
        return forOrder(topic, orderId).size();
    }

    /** Re-publishes the exact captured bytes (same key, same eventId) to the same topic. */
    public void republish(Rec rec) {
        send(rec.topic(), rec.key(), rec.rawValue());
    }

    public void send(String topic, String key, String value) {
        try {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot publish to " + topic, e);
        }
    }

    @Override
    public void close() {
        running = false;
        consumer.wakeup();
        try {
            poller.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        producer.close(Duration.ofSeconds(2));
    }
}
