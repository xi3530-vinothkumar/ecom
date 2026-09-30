package com.ecom.platform.kafka;

import com.ecom.common.events.EventEnvelope;
import com.ecom.platform.core.CallContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The only way services publish events. Writes an outbox row that commits or rolls back together
 * with the caller's business change; {@link OutboxRelay} publishes it afterwards (at-least-once).
 * Must be called inside an active transaction.
 */
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String producerService;
    private final ObjectProvider<Tracer> tracer;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper, String producerService, ObjectProvider<Tracer> tracer) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.producerService = producerService;
        this.tracer = tracer;
    }

    /** Publishes {@code payload} to {@code topic} (event type = topic name), version 1. */
    public EventEnvelope publish(String topic, String key, Object payload) {
        return publish(topic, 1, key, payload);
    }

    public EventEnvelope publish(String topic, int eventVersion, String key, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OutboxWriter.publish must run inside a transaction");
        }
        EventEnvelope envelope = EventEnvelope.create(
                topic, eventVersion, CallContext.correlationIdOrNull(), producerService, payload, mapper);
        try {
            jdbc.update(
                    "INSERT INTO outbox (event_id, topic, message_key, payload, traceparent) VALUES (?, ?, ?, ?, ?)",
                    envelope.eventId(), topic, key, mapper.writeValueAsString(envelope), currentTraceparent());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize event for " + topic, e);
        }
        return envelope;
    }

    private String currentTraceparent() {
        Tracer t = tracer.getIfAvailable();
        Span span = t == null ? null : t.currentSpan();
        if (span == null) {
            return null;
        }
        return "00-" + span.context().traceId() + "-" + span.context().spanId() + "-01";
    }
}
