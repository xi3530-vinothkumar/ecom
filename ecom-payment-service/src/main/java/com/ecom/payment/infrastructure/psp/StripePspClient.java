package com.ecom.payment.infrastructure.psp;

import com.ecom.payment.application.psp.AuthorizationResult;
import com.ecom.payment.application.psp.AuthorizationResult.CardDetails;
import com.ecom.payment.application.psp.PspClient;
import com.ecom.payment.application.psp.PspException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Stripe PaymentIntents API (form-encoded). Authorization = PaymentIntent with
 * {@code capture_method=manual, confirm=true}; the order id is both the {@code Idempotency-Key} and
 * {@code metadata[order_id]} (so webhooks can be correlated even before our row commits).
 */
public class StripePspClient implements PspClient {

    private static final Logger log = LoggerFactory.getLogger(StripePspClient.class);
    private static final String UNEXPECTED_STATE = "payment_intent_unexpected_state";

    private final RestClient http;
    private final ObjectMapper mapper;

    /** {@code http} must already carry base URL, bearer auth and timeouts (see {@link #configure}). */
    public StripePspClient(RestClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    public static RestClient.Builder configure(RestClient.Builder builder, String baseUrl, String apiKey) {
        return builder.baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
    }

    private record Response(int status, JsonNode body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        JsonNode error() {
            return body.path("error");
        }
    }

    @Override
    public AuthorizationResult authorize(String idempotencyKey, long amountMinor, String currency, String paymentMethodRef) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount", Long.toString(amountMinor));
        form.add("currency", currency.toLowerCase(Locale.ROOT));
        form.add("payment_method", paymentMethodRef);
        form.add("payment_method_types[]", "card");
        form.add("capture_method", "manual");
        form.add("confirm", "true");
        form.add("metadata[order_id]", idempotencyKey);
        form.add("expand[]", "payment_method");
        Response res = post("/v1/payment_intents", idempotencyKey, form);
        if (res.ok()) {
            return fromIntent(res.body());
        }
        JsonNode error = res.error();
        String type = error.path("type").asText("");
        // 402 card_error = the issuer declined; other 400s = the token/request can never succeed
        if (res.status() == 402 || (res.status() == 400 && !"idempotency_error".equals(type))) {
            JsonNode intent = error.path("payment_intent");
            String reason = firstNonBlank(error.path("decline_code").asText(null), error.path("code").asText(null), type);
            return new AuthorizationResult.Declined(
                    intent.path("id").asText(null), reason == null ? "declined" : reason, card(error.path("payment_method")));
        }
        throw failure("authorize", res);
    }

    @Override
    public void cancel(String pspReference) {
        Response res = post("/v1/payment_intents/" + pspReference + "/cancel", "cancel-" + pspReference, new LinkedMultiValueMap<>());
        if (res.ok() || alreadyIn(res, "canceled")) {
            return;
        }
        throw failure("cancel", res);
    }

    @Override
    public void capture(String pspReference) {
        Response res = post("/v1/payment_intents/" + pspReference + "/capture", "capture-" + pspReference, new LinkedMultiValueMap<>());
        if (res.ok() || alreadyIn(res, "succeeded")) {
            return;
        }
        throw failure("capture", res);
    }

    private AuthorizationResult fromIntent(JsonNode intent) {
        String id = intent.path("id").asText();
        String status = intent.path("status").asText();
        CardDetails card = card(intent.path("payment_method"));
        return switch (status) {
            case "requires_capture", "succeeded" -> new AuthorizationResult.Authorized(id, card);
            // processing: async method; the outcome arrives by webhook just like a 3DS challenge
            case "requires_action", "processing" ->
                    new AuthorizationResult.RequiresAction(id, intent.path("client_secret").asText(), card);
            case "requires_payment_method", "canceled" -> {
                JsonNode err = intent.path("last_payment_error");
                String reason = firstNonBlank(err.path("decline_code").asText(null), err.path("code").asText(null), status);
                yield new AuthorizationResult.Declined(id, reason, card);
            }
            default -> throw new PspException("Unexpected payment_intent status " + status + " for " + id);
        };
    }

    private static boolean alreadyIn(Response res, String status) {
        JsonNode error = res.error();
        return res.status() == 400
                && UNEXPECTED_STATE.equals(error.path("code").asText())
                && status.equals(error.path("payment_intent").path("status").asText());
    }

    private static CardDetails card(JsonNode paymentMethod) {
        JsonNode card = paymentMethod.path("card");
        if (card.isMissingNode()) {
            return CardDetails.UNKNOWN;
        }
        return new CardDetails(card.path("brand").asText(null), card.path("last4").asText(null));
    }

    private Response post(String path, String idempotencyKey, MultiValueMap<String, String> form) {
        try {
            return http.post()
                    .uri(path)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((req, res) -> {
                        byte[] bytes = res.getBody().readAllBytes();
                        JsonNode body = bytes.length == 0 ? mapper.createObjectNode() : readJson(bytes);
                        return new Response(res.getStatusCode().value(), body);
                    });
        } catch (ResourceAccessException e) {
            throw new PspException("Stripe unreachable: " + path, e);
        }
    }

    private JsonNode readJson(byte[] bytes) {
        try {
            return mapper.readTree(bytes);
        } catch (IOException e) {
            return mapper.createObjectNode();
        }
    }

    private static PspException failure(String operation, Response res) {
        JsonNode error = res.error();
        String message = "Stripe " + operation + " failed: HTTP " + res.status() + " "
                + error.path("type").asText("") + "/" + error.path("code").asText("");
        log.warn(message);
        return new PspException(message);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
