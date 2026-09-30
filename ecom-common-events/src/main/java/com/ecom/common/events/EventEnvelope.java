package com.ecom.common.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;

/**
 * Envelope wrapped around every event on Kafka. Schema evolution is governed by {@code eventVersion}
 * plus additive-fields-only discipline (no schema registry). Payloads carry IDs and Money, never PII.
 * The payload stays a {@link JsonNode} so consumers can branch on {@code eventVersion} before binding.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String correlationId,
        String producerService,
        JsonNode payload) {

    public <T> T payloadAs(ObjectMapper mapper, Class<T> type) {
        try {
            return mapper.treeToValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot bind payload of " + eventType + " to " + type.getSimpleName(), e);
        }
    }

    public static EventEnvelope create(
            String eventType, int eventVersion, String correlationId, String producerService, Object payload,
            ObjectMapper mapper) {
        return new EventEnvelope(
                UUID.randomUUID(), eventType, eventVersion, Instant.now(), correlationId, producerService,
                mapper.valueToTree(payload));
    }

    public static EventEnvelope parse(ObjectMapper mapper, String json) {
        try {
            return mapper.readValue(json, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed event envelope", e);
        }
    }
}
