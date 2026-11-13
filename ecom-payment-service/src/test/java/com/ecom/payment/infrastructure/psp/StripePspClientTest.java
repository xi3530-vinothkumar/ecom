package com.ecom.payment.infrastructure.psp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ecom.payment.application.psp.AuthorizationResult;
import com.ecom.payment.application.psp.PspException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class StripePspClientTest {

    private static final String BASE = "https://stripe.test";

    private MockRestServiceServer server;
    private StripePspClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = StripePspClient.configure(RestClient.builder(), BASE, "sk_test_123");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new StripePspClient(builder.build(), new ObjectMapper());
    }

    @Test
    void authorizeSendsManualCaptureConfirmWithIdempotencyKey() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("amount", "2590");
        form.add("currency", "eur");
        form.add("payment_method", "pm_card_visa");
        form.add("payment_method_types[]", "card");
        form.add("capture_method", "manual");
        form.add("confirm", "true");
        form.add("metadata[order_id]", "order-1");
        form.add("expand[]", "payment_method");
        server.expect(requestTo(BASE + "/v1/payment_intents"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk_test_123"))
                .andExpect(header("Idempotency-Key", "order-1"))
                .andExpect(content().formData(form))
                .andRespond(withSuccess("""
                        {"id":"pi_1","object":"payment_intent","status":"requires_capture",
                         "payment_method":{"id":"pm_1","card":{"brand":"visa","last4":"4242"}}}""",
                        MediaType.APPLICATION_JSON));

        var result = client.authorize("order-1", 2590, "EUR", "pm_card_visa");

        assertThat(result).isEqualTo(new AuthorizationResult.Authorized(
                "pi_1", new AuthorizationResult.CardDetails("visa", "4242")));
        server.verify();
    }

    @Test
    void requiresActionCarriesClientSecret() {
        server.expect(requestTo(BASE + "/v1/payment_intents"))
                .andRespond(withSuccess("""
                        {"id":"pi_2","status":"requires_action","client_secret":"pi_2_secret_x","payment_method":"pm_x"}""",
                        MediaType.APPLICATION_JSON));
        var result = client.authorize("order-2", 100, "EUR", "pm_x");
        assertThat(result).isInstanceOfSatisfying(AuthorizationResult.RequiresAction.class, r -> {
            assertThat(r.pspReference()).isEqualTo("pi_2");
            assertThat(r.clientSecret()).isEqualTo("pi_2_secret_x");
        });
    }

    @Test
    void cardErrorIsADecline() {
        server.expect(requestTo(BASE + "/v1/payment_intents"))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"type":"card_error","code":"card_declined","decline_code":"insufficient_funds",
                          "payment_intent":{"id":"pi_3","status":"requires_payment_method"}}}"""));
        var result = client.authorize("order-3", 100, "EUR", "pm_y");
        assertThat(result).isEqualTo(new AuthorizationResult.Declined("pi_3", "insufficient_funds", null));
    }

    @Test
    void serverErrorsAreRetryableFailures() {
        server.expect(requestTo(BASE + "/v1/payment_intents"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\":{\"type\":\"api_error\"}}"));
        assertThatThrownBy(() -> client.authorize("order-4", 100, "EUR", "pm_y")).isInstanceOf(PspException.class);
    }

    @Test
    void cancelAndCaptureTolerateAlreadyReachedState() {
        server.expect(requestTo(BASE + "/v1/payment_intents/pi_5/cancel"))
                .andExpect(header("Idempotency-Key", "cancel-pi_5"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"type":"invalid_request_error","code":"payment_intent_unexpected_state",
                          "payment_intent":{"id":"pi_5","status":"canceled"}}}"""));
        server.expect(requestTo(BASE + "/v1/payment_intents/pi_6/capture"))
                .andExpect(header("Idempotency-Key", "capture-pi_6"))
                .andRespond(withSuccess("{\"id\":\"pi_6\",\"status\":\"succeeded\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/payment_intents/pi_7/capture"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"type":"invalid_request_error","code":"payment_intent_unexpected_state",
                          "payment_intent":{"id":"pi_7","status":"canceled"}}}"""));

        client.cancel("pi_5");
        client.capture("pi_6");
        assertThatThrownBy(() -> client.capture("pi_7")).isInstanceOf(PspException.class);
        server.verify();
    }
}
