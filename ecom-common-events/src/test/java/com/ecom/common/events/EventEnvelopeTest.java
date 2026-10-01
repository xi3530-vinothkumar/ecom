package com.ecom.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.common.events.payload.OrderConfirmedPayload;
import com.ecom.common.events.payload.OrderLinePayload;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class EventEnvelopeTest {

    // java.time support is built into Jackson 3
    private final ObjectMapper mapper = new JsonMapper();

    @Test
    void serializesAndBindsPayload() throws Exception {
        var payload = new OrderConfirmedPayload(
                "o-1", "u-1", List.of(new OrderLinePayload("SKU-1", 2)), Money.of("12.50", "EUR"));
        var env = EventEnvelope.create(EventTopics.ORDER_CONFIRMED, 1, "corr-1", "order-service", payload, mapper);

        var parsed = EventEnvelope.parse(mapper, mapper.writeValueAsString(env));

        assertThat(parsed.eventId()).isEqualTo(env.eventId());
        assertThat(parsed.payloadAs(mapper, OrderConfirmedPayload.class)).isEqualTo(payload);
    }

    @Test
    void toleratesUnknownFieldsFromNewerProducers() {
        var json = """
                {"eventId":"8f14e45f-ceea-467a-9d6e-3c5f8a3b8e2a","eventType":"x","eventVersion":2,
                 "occurredAt":"2026-01-01T00:00:00Z","correlationId":"c","producerService":"p",
                 "payload":{"orderId":"o","userId":"u","lines":[],"total":{"currencyCode":"EUR","units":1,"nanos":0},
                 "newField":"ignored"}}""";
        var lenient = mapper.rebuild()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
        var env = EventEnvelope.parse(lenient, json);
        assertThat(env.payloadAs(lenient, OrderConfirmedPayload.class).orderId()).isEqualTo("o");
    }
}
