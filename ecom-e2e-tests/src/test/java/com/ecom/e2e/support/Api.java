package com.ecom.e2e.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calls the services' REST APIs directly, stamping the headers the Gateway would add
 * (X-User-Id, X-User-Roles, X-Correlation-Id). The Gateway itself is covered by its own tests.
 */
public final class Api {

    public static final String ADMIN_ID = UUID.randomUUID().toString();

    public record Resp(int status, JsonNode body, String raw) {
        public Resp expect(int... expected) {
            for (int e : expected) {
                if (status == e) {
                    return this;
                }
            }
            throw new AssertionError("Expected HTTP " + java.util.Arrays.toString(expected) + " but got " + status
                    + ": " + raw);
        }

        public String text(String field) {
            return body.path(field).asText();
        }
    }

    /** Caller identity as the Gateway would stamp it. */
    public record Caller(String userId, String roles) {
        public static Caller admin() {
            return new Caller(ADMIN_ID, "ADMIN");
        }

        public static Caller customer(String userId) {
            return new Caller(userId, "CUSTOMER");
        }

        public static Caller anonymous() {
            return new Caller(null, null);
        }
    }

    private final EcomSystem sys;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Api(EcomSystem sys) {
        this.sys = sys;
    }

    public Resp call(String svc, String method, String path, Caller caller, Object body, Map<String, String> headers) {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + sys.httpPort(svc) + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("X-Correlation-Id", "e2e-" + UUID.randomUUID());
        if (caller != null && caller.userId() != null) {
            b.header("X-User-Id", caller.userId());
        }
        if (caller != null && caller.roles() != null) {
            b.header("X-User-Roles", caller.roles());
        }
        headers.forEach(b::header);
        try {
            if (body == null) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                b.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)));
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = r.body() == null || r.body().isBlank() ? MissingNode.getInstance() : parse(r.body());
            return new Resp(r.statusCode(), node, r.body());
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + svc + path + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode parse(String s) {
        try {
            return Json.MAPPER.readTree(s);
        } catch (JsonProcessingException e) {
            return MissingNode.getInstance();
        }
    }

    public Resp call(String svc, String method, String path, Caller caller, Object body) {
        return call(svc, method, path, caller, body, Map.of());
    }

    // ---------------------------------------------------------------- catalog + inventory (admin)

    public String createCategory(String name) {
        return call("catalog", "POST", "/admin/categories", Caller.admin(), Map.of("name", name))
                .expect(201).text("id");
    }

    public String createProduct(String categoryId, String sku, String price) {
        Map<String, Object> variant = new LinkedHashMap<>();
        variant.put("sku", sku);
        variant.put("variantName", "Default " + sku);
        variant.put("price", Map.of("amount", price, "currency", "EUR"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "E2E product " + sku);
        body.put("description", "created by ecom-e2e-tests");
        body.put("categoryId", categoryId);
        body.put("brand", "E2E");
        body.put("variants", List.of(variant));
        return call("catalog", "POST", "/admin/products", Caller.admin(), body).expect(201).text("id");
    }

    public JsonNode setStock(String sku, int onHand) {
        return call("inventory", "PUT", "/admin/inventory/" + sku, Caller.admin(), Map.of("onHand", onHand))
                .expect(200).body();
    }

    public Stock stock(String sku) {
        JsonNode n = call("inventory", "GET", "/admin/inventory/" + sku, Caller.admin(), null).expect(200).body();
        return new Stock(n.path("onHand").asInt(), n.path("reserved").asInt(), n.path("available").asInt());
    }

    public record Stock(int onHand, int reserved, int available) {}

    // ---------------------------------------------------------------- user

    public String register(String email) {
        return call("user", "POST", "/users", Caller.anonymous(), Map.of(
                "email", email, "password", "E2e-Passw0rd!", "fullName", "E2E Shopper")).expect(201).text("id");
    }

    public String addAddress(String userId) {
        return call("user", "POST", "/users/me/addresses", Caller.customer(userId), Map.of(
                "line1", "Damrak 1", "city", "Amsterdam", "postalCode", "1012LG", "countryCode", "NL"))
                .expect(201).text("id");
    }

    // ---------------------------------------------------------------- cart + order

    public Resp setCartItem(String userId, String sku, int qty) {
        return call("cart", "PUT", "/cart/items/" + sku, Caller.customer(userId), Map.of("quantity", qty)).expect(200);
    }

    public JsonNode cart(String userId) {
        return call("cart", "GET", "/cart", Caller.customer(userId), null).expect(200).body();
    }

    public int cartQty(String userId, String sku) {
        for (JsonNode line : cart(userId).path("lines")) {
            if (sku.equals(line.path("sku").asText())) {
                return line.path("quantity").asInt();
            }
        }
        return 0;
    }

    public Resp checkout(String userId, String idempotencyKey, String paymentMethodRef, String addressId) {
        return call("order", "POST", "/orders", Caller.customer(userId),
                Map.of("paymentMethodRef", paymentMethodRef, "shippingAddressId", addressId),
                Map.of("Idempotency-Key", idempotencyKey));
    }

    public JsonNode order(String userId, String orderId) {
        return call("order", "GET", "/orders/" + orderId, Caller.customer(userId), null).expect(200).body();
    }

    public JsonNode myOrders(String userId) {
        return call("order", "GET", "/orders?size=100", Caller.customer(userId), null).expect(200).body();
    }

    // ---------------------------------------------------------------- payment (fake PSP dev hook)

    public Resp completeAction(String pspReference, String outcome) {
        return call("payment", "POST", "/dev/psp/complete-action/" + pspReference + "?outcome=" + outcome,
                Caller.anonymous(), null);
    }
}
