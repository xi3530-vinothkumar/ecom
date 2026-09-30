package com.ecom.cart.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

@AutoConfigureTestRestTemplate
class CartApiIT extends AbstractCartIT {

    @Autowired TestRestTemplate rest;

    private static HttpHeaders guest(String guestId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (guestId != null) {
            h.set("X-Guest-Cart-Id", guestId);
        }
        return h;
    }

    private static HttpHeaders user(String userId) {
        HttpHeaders h = guest(null);
        h.set("X-User-Id", userId);
        return h;
    }

    private ResponseEntity<JsonNode> put(HttpHeaders h, String sku, Object body) {
        return rest.exchange("/cart/items/" + sku, HttpMethod.PUT, new HttpEntity<>(body, h), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(HttpHeaders h) {
        return rest.exchange("/cart", HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
    }

    private String newGuestId() {
        ResponseEntity<JsonNode> res = rest.postForEntity("/cart/guest", null, JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody().path("guestCartId").asString();
    }

    @Test
    void guestAddUpdateRemoveWithHydrationAndSlidingTtl() {
        String a = sku("A"), b = sku("B");
        CATALOG.add(a, "Mug", 12, 500_000_000, true);
        CATALOG.add(b, "Tea", 3, 0, true);
        String guestId = newGuestId();
        HttpHeaders h = guest(guestId);

        ResponseEntity<JsonNode> added = put(h, a, Map.of("quantity", 2));
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.OK);
        put(h, b, Map.of("quantity", 1));
        JsonNode cart = put(h, a, Map.of("quantity", 3)).getBody();

        assertThat(cart.path("lines")).hasSize(2);
        JsonNode mug = cart.path("lines").get(0);
        assertThat(mug.path("sku").asString()).isEqualTo(a);
        assertThat(mug.path("name").asString()).isEqualTo("Mug");
        assertThat(mug.path("quantity").asInt()).isEqualTo(3);
        assertThat(mug.path("unitPrice").path("amount").asString()).isEqualTo("12.50");
        assertThat(mug.path("lineTotal").path("amount").asString()).isEqualTo("37.50");
        assertThat(mug.path("available").asBoolean()).isTrue();
        assertThat(cart.path("subtotal").path("amount").asString()).isEqualTo("40.50");
        assertThat(cart.path("subtotal").path("currency").asString()).isEqualTo("EUR");
        assertThat(cart.path("itemCount").asInt()).isEqualTo(4);

        String key = "cart:guest:" + guestId;
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isBetween(Duration.ofDays(30).toSeconds() - 60, Duration.ofDays(30).toSeconds());

        redis.expire(key, Duration.ofDays(1));
        assertThat(rest.exchange("/cart/items/" + b, HttpMethod.DELETE, new HttpEntity<>(h), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isGreaterThan(Duration.ofDays(29).toSeconds());

        JsonNode after = get(h).getBody();
        assertThat(after.path("lines")).hasSize(1);
        assertThat(after.path("subtotal").path("amount").asString()).isEqualTo("37.50");

        assertThat(rest.exchange("/cart", HttpMethod.DELETE, new HttpEntity<>(h), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(redis.hasKey(key)).isFalse();
        JsonNode empty = get(h).getBody();
        assertThat(empty.path("lines")).isEmpty();
        assertThat(empty.path("subtotal").path("amount").asString()).isEqualTo("0.00");
    }

    @Test
    void userCartHasNoTtl() {
        String a = sku("A");
        CATALOG.add(a, "Pen", 1, 0, true);
        String userId = userId();

        put(user(userId), a, Map.of("quantity", 1));

        assertThat(redis.opsForHash().get("cart:user:" + userId, a)).isEqualTo("1");
        assertThat(redis.getExpire("cart:user:" + userId)).isEqualTo(-1L);
        assertThat(get(user(userId)).getBody().path("lines")).hasSize(1);
    }

    @Test
    void unknownAndInactiveSkusAreFlaggedUnavailableAndExcludedFromSubtotal() {
        String ok = sku("OK"), inactive = sku("OFF"), gone = sku("GONE");
        CATALOG.add(ok, "Book", 10, 0, true);
        CATALOG.add(inactive, "Old", 99, 0, false);
        String userId = userId();
        redis.opsForHash().putAll("cart:user:" + userId, Map.of(ok, "1", inactive, "2", gone, "1"));

        JsonNode cart = get(user(userId)).getBody();

        assertThat(cart.path("catalogReachable").asBoolean()).isTrue();
        Map<String, Boolean> availability = new java.util.HashMap<>();
        cart.path("lines").forEach(l -> availability.put(l.path("sku").asString(), l.path("available").asBoolean()));
        assertThat(availability).containsExactlyInAnyOrderEntriesOf(Map.of(ok, true, inactive, false, gone, false));
        assertThat(cart.path("subtotal").path("amount").asString()).isEqualTo("10.00");
    }

    @Test
    void hydrationIsServedFromL1CacheOnRepeatReads() {
        String a = sku("A");
        CATALOG.add(a, "Cup", 2, 0, true);
        String userId = userId();
        redis.opsForHash().put("cart:user:" + userId, a, "1");

        get(user(userId));
        int calls = CATALOG.batchCalls.get();
        get(user(userId));
        get(user(userId));

        assertThat(CATALOG.batchCalls.get()).isEqualTo(calls);
    }

    @Test
    void validationErrors() {
        String a = sku("A"), inactive = sku("OFF");
        CATALOG.add(a, "Pen", 1, 0, true);
        CATALOG.add(inactive, "Old", 1, 0, false);
        HttpHeaders h = guest(newGuestId());

        assertThat(get(guest(null)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(guest("not-a-uuid")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(h, a, Map.of("quantity", 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(h, a, Map.of("quantity", 100)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(h, a, Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(h, sku("UNKNOWN"), Map.of("quantity", 1)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(h, inactive, Map.of("quantity", 1)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(put(h, a, Map.of("quantity", 99)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
