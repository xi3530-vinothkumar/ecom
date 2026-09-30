package com.ecom.inventory.it;

import static com.ecom.testing.RestCalls.exchange;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.payload.OrderCreatedPayload;
import com.ecom.common.events.payload.StockLevelChangedPayload;
import com.ecom.inventory.infrastructure.OrderEventsListener;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@AutoConfigureRestTestClient
class AdminInventoryApiIT extends AbstractInventoryIT {

    @Autowired RestTestClient rest;
    @Autowired OrderEventsListener listener;

    private static HttpHeaders headers(String roles) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-User-Id", "admin-1");
        if (roles != null) {
            h.set("X-User-Roles", roles);
        }
        return h;
    }

    private ResponseEntity<JsonNode> put(String sku, Object body, String roles) {
        return exchange(rest, HttpMethod.PUT, "/admin/inventory/" + sku, body, headers(roles), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String sku, String roles) {
        return exchange(rest, HttpMethod.GET, "/admin/inventory/" + sku, null, headers(roles), JsonNode.class);
    }

    @Test
    void putCreatesAndUpdatesStockAndEmitsAvailability() {
        String s = sku("ADM");

        ResponseEntity<JsonNode> created = put(s, Map.of("onHand", 7), "ADMIN");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody().path("onHand").asInt()).isEqualTo(7);
        assertThat(created.getBody().path("available").asInt()).isEqualTo(7);
        assertThat(created.getBody().path("warehouseId").asString()).isEqualTo("DEFAULT");

        put(s, Map.of("onHand", 9), "ADMIN");
        put(s, Map.of("onHand", 9), "ADMIN"); // unchanged: no new event

        ResponseEntity<JsonNode> fetched = get(s, "ADMIN");
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().path("onHand").asInt()).isEqualTo(9);
        var events = outboxEvents(EventTopics.STOCK_LEVEL_CHANGED, s).stream()
                .map(e -> e.payloadAs(mapper, StockLevelChangedPayload.class))
                .toList();
        assertThat(events).extracting(StockLevelChangedPayload::availableQty).containsExactly(7, 9);
        assertThat(events.get(1).version()).isGreaterThan(events.get(0).version());
    }

    @Test
    void onHandBelowReservedIsRejectedWith422() {
        String s = sku("ADM");
        put(s, Map.of("onHand", 5), "ADMIN");
        UUID orderId = UUID.randomUUID();
        listener.onOrderCreated(record(EventTopics.ORDER_CREATED, orderId.toString(),
                new OrderCreatedPayload(orderId.toString(), "u", lines(s, 4))));

        ResponseEntity<JsonNode> res = put(s, Map.of("onHand", 3), "ADMIN");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(level(s).onHand()).isEqualTo(5);
        assertThat(put(s, Map.of("onHand", 4), "ADMIN").getBody().path("available").asInt()).isZero();
    }

    @Test
    void requiresAdminRole() {
        String s = sku("ADM");
        assertThat(put(s, Map.of("onHand", 1), "CUSTOMER").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(s, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        HttpHeaders anonymous = new HttpHeaders();
        assertThat(exchange(rest, HttpMethod.GET, "/admin/inventory/" + s, null, anonymous, JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validationAndNotFound() {
        assertThat(get(sku("NOPE"), "ADMIN").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(sku("ADM"), Map.of("onHand", -1), "ADMIN").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(sku("ADM"), Map.of(), "ADMIN").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
