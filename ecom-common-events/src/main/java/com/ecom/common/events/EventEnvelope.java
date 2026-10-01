package com.ecom.common.events;

import com.ecom.common.events.payload.EventPayload;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

    public <T extends EventPayload> T payloadAs(ObjectMapper mapper, Class<T> type) {
        try {
            return mapper.treeToValue(payload, type);
        } catch (JacksonException e) {
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
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Malformed event envelope", e);
        }
    }
}
