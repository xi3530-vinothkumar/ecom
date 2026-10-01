package com.ecom.platform.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls unpublished outbox rows and sends them to Kafka. Rows are locked with SKIP LOCKED so several
 * replicas can relay concurrently without double-sending the same row in the same poll. A failed
 * send rolls back the batch; rows are retried on the next poll (consumers dedup on eventId).
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxProperties props;
    private final Counter published;
    private final Counter failures;

    public OutboxRelay(
            JdbcTemplate jdbc,
            KafkaTemplate<String, String> kafka,
            TransactionTemplate tx,
            OutboxProperties props,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.props = props;
        this.published = Counter.builder("ecom.outbox.published").register(meters);
        this.failures = Counter.builder("ecom.outbox.publish.failures").register(meters);
        Gauge.builder("ecom.outbox.pending", this, OutboxRelay::pendingCount).register(meters);
    }

    private record Row(long id, String topic, String key, String payload, String traceparent) {}

    @Scheduled(fixedDelayString = "${ecom.outbox.poll-interval:200ms}")
    public void relay() {
        try {
            Integer sent;
            do {
                sent = tx.execute(status -> publishBatch());
            } while (sent != null && sent == props.batchSize());
        } catch (RuntimeException e) {
            failures.increment();
            log.warn("Outbox relay batch failed; will retry next poll: {}", e.toString());
        }
    }

    /** Publishes one batch; returns the number of rows published. Visible for tests. */
    public int publishBatch() {
        List<Row> rows = jdbc.query(
                """
                SELECT id, topic, message_key, payload, traceparent FROM outbox WHERE published_at IS NULL
                ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED""",
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                props.batchSize());
        if (rows.isEmpty()) {
            return 0;
        }
        List<CompletableFuture<?>> futures = new ArrayList<>(rows.size());
        for (Row row : rows) {
            ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
            if (row.traceparent() != null) {
                record.headers().add("traceparent", row.traceparent().getBytes(StandardCharsets.UTF_8));
            }
            futures.add(kafka.send(record));
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Kafka send failed for outbox batch", e);
        }
        List<Object[]> ids = rows.stream().map(r -> new Object[] {r.id()}).toList();
        jdbc.batchUpdate("UPDATE outbox SET published_at = now() WHERE id = ?", ids);
        published.increment(rows.size());
        return rows.size();
    }

    @Scheduled(cron = "${ecom.outbox.cleanup-cron:0 17 * * * *}")
    public void cleanup() {
        Timestamp outboxCutoff = Timestamp.from(Instant.now().minus(props.publishedRetention()));
        Timestamp dedupCutoff = Timestamp.from(Instant.now().minus(props.processedEventsRetention()));
        int o = jdbc.update("DELETE FROM outbox WHERE published_at IS NOT NULL AND published_at < ?", outboxCutoff);
        int p = jdbc.update("DELETE FROM processed_events WHERE processed_at < ?", dedupCutoff);
        log.info("Outbox cleanup removed {} published rows and {} processed_events rows", o, p);
    }

    private double pendingCount() {
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class);
            return n == null ? 0 : n;
        } catch (RuntimeException _) {
            return Double.NaN;
        }
    }

    public static Duration defaultPollInterval() {
        return Duration.ofMillis(200);
    }
}
