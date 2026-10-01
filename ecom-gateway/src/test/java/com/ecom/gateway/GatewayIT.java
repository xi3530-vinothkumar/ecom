package com.ecom.gateway;

import com.ecom.testing.ContainerConfigs;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@Import({ContainerConfigs.Redis.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class GatewayIT {

    static final StubBackend STUB = new StubBackend();
    static final TestTokens TOKENS = new TestTokens();

    static {
        STUB.setJwks(TOKENS.jwksJson());
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        for (String svc : List.of("user", "catalog", "inventory", "cart", "order", "payment", "search")) {
            r.add("ecom.gateway.backends." + svc, STUB::baseUrl);
        }
        r.add("ecom.gateway.jwt.jwks-uri", () -> STUB.baseUrl() + StubBackend.GATEWAY_JWKS_PATH);
        r.add("ecom.gateway.rate-limits.login.replenish-rate", () -> "1");
        r.add("ecom.gateway.rate-limits.login.burst-capacity", () -> "3");
        r.add("ecom.gateway.rate-limits.login.requested-tokens", () -> "1");
    }

    @Autowired WebTestClient client;

    private static String unique(String prefix) {
        return prefix + "/" + UUID.randomUUID();
    }

    @Test
    void publicGetWorksWithoutTokenAndCarriesNoIdentity() {
        String path = unique("/products");
        client.get().uri(path).exchange().expectStatus().isOk();

        var req = STUB.single(path);
        assertThat(req.header("X-User-Id")).isEmpty();
        assertThat(req.header("X-User-Roles")).isEmpty();
    }

    @Test
    void spoofedIdentityHeadersAreRemovedForAnonymousCalls() {
        String path = unique("/products");
        client.get().uri(path)
                .header("X-User-Id", "attacker").header("X-User-Roles", "ADMIN").header("x-user-email", "a@b.c")
                .exchange().expectStatus().isOk();

        var req = STUB.single(path);
        assertThat(req.header("X-User-Id")).isEmpty();
        assertThat(req.header("X-User-Roles")).isEmpty();
        assertThat(req.header("X-User-Email")).isEmpty();
    }

    @Test
    void spoofedIdentityIsOverwrittenByValidatedClaimsAndJwtIsNotForwarded() {
        String sub = UUID.randomUUID().toString();
        String path = unique("/orders");
        client.get().uri(path)
                .headers(h -> h.setBearerAuth(TOKENS.valid(sub, "CUSTOMER")))
                .header("X-User-Id", "attacker").header("X-User-Roles", "ADMIN")
                .exchange().expectStatus().isOk();

        var req = STUB.single(path);
        assertThat(req.headers().get("X-User-Id")).containsExactly(sub);
        assertThat(req.headers().get("X-User-Roles")).containsExactly("CUSTOMER");
        assertThat(req.header(HttpHeaders.AUTHORIZATION)).isEmpty();
    }

    @Test
    void protectedRouteWithoutTokenIs401() {
        String path = unique("/orders");
        client.get().uri(path).exchange().expectStatus().isUnauthorized();
        assertThat(STUB.requestsTo(path)).isEmpty();
    }

    @Test
    void expiredUnknownKidWrongAudienceOrIssuerTokensAre401() {
        String sub = UUID.randomUUID().toString();
        for (String token : List.of(TOKENS.expired(sub), TOKENS.unknownKid(sub), TOKENS.wrongAudience(sub),
                TOKENS.wrongIssuer(sub), "not.a.jwt")) {
            String path = unique("/orders");
            client.get().uri(path).headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isUnauthorized();
            assertThat(STUB.requestsTo(path)).isEmpty();
        }
    }

    @Test
    void customerOnAdminRouteIs403AndAdminPasses() {
        String path = unique("/admin/products");
        client.post().uri(path).headers(h -> h.setBearerAuth(TOKENS.valid(UUID.randomUUID().toString(), "CUSTOMER")))
                .exchange().expectStatus().isForbidden();
        assertThat(STUB.requestsTo(path)).isEmpty();

        client.post().uri(path).headers(h -> h.setBearerAuth(TOKENS.valid(UUID.randomUUID().toString(), "ADMIN", "CUSTOMER")))
                .exchange().expectStatus().isOk();
        assertThat(STUB.single(path).headers().get("X-User-Roles")).containsExactly("ADMIN,CUSTOMER");
    }

    @Test
    void cartIsPublicButHonorsAValidToken() {
        String guestPath = unique("/cart");
        client.get().uri(guestPath).header("X-Guest-Cart-Id", "g-1").exchange().expectStatus().isOk();
        assertThat(STUB.single(guestPath).header("X-Guest-Cart-Id")).contains("g-1");
        assertThat(STUB.single(guestPath).header("X-User-Id")).isEmpty();

        String sub = UUID.randomUUID().toString();
        String userPath = unique("/cart");
        client.get().uri(userPath).headers(h -> h.setBearerAuth(TOKENS.valid(sub, "CUSTOMER"))).exchange()
                .expectStatus().isOk();
        assertThat(STUB.single(userPath).header("X-User-Id")).contains(sub);
    }

    @Test
    void staleTokenDoesNotBlockCredentialEndpoints() {
        client.post().uri("/auth/refresh").headers(h -> h.setBearerAuth(TOKENS.expired("x")))
                .exchange().expectStatus().isOk();
        assertThat(STUB.requestsTo("/auth/refresh")).last()
                .satisfies(r -> assertThat(r.header(HttpHeaders.AUTHORIZATION)).isEmpty());
    }

    @Test
    void mobileCredentialEndpointsArePublicAndIgnoreStaleTokens() {
        // mobile login is covered by the shared login bucket; exercising it here would drain loginIsRateLimitedPerClient
        for (String path : List.of("/auth/mobile/refresh", "/auth/mobile/logout")) {
            client.post().uri(path).headers(h -> h.setBearerAuth(TOKENS.expired("x")))
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"refreshToken\":\"t\"}")
                    .exchange().expectStatus().isOk();
            assertThat(STUB.requestsTo(path)).last()
                    .satisfies(r -> assertThat(r.header(HttpHeaders.AUTHORIZATION)).isEmpty());
        }
    }

    @Test
    void loginIsRateLimitedPerClient() {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            statuses.add(client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"email\":\"a@b.c\",\"password\":\"x\"}")
                    .exchange().returnResult(String.class).getStatus().value());
        }
        assertThat(statuses.getFirst()).isEqualTo(200);
        assertThat(statuses).contains(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void backendJwksAndActuatorAreNotRoutable() {
        String token = TOKENS.valid(UUID.randomUUID().toString(), "ADMIN");
        for (String path : List.of("/.well-known/jwks.json", "/actuator/env", "/actuator/prometheus/../env")) {
            client.get().uri(path).headers(h -> h.setBearerAuth(token)).exchange()
                    .expectStatus().value(s -> assertThat(s).isIn(400, 401, 403, 404));
        }
        client.get().uri("/.well-known/jwks.json").exchange().expectStatus().isUnauthorized();
        assertThat(STUB.requestsTo("/.well-known/jwks.json")).isEmpty();
        assertThat(STUB.requestsTo("/actuator/env")).isEmpty();
    }

    @Test
    void devPspRoutesAreDisabledByDefault() {
        client.post().uri("/dev/psp/complete-action/pi_1")
                .headers(h -> h.setBearerAuth(TOKENS.valid(UUID.randomUUID().toString(), "CUSTOMER")))
                .exchange().expectStatus().isNotFound();
        assertThat(STUB.requestsTo("/dev/psp/complete-action/pi_1")).isEmpty();
    }

    @Test
    void correlationIdIsKeptWhenUuidAndReplacedOtherwise() {
        String good = UUID.randomUUID().toString();
        String path1 = unique("/products");
        client.get().uri(path1).header("X-Correlation-Id", good).exchange()
                .expectStatus().isOk().expectHeader().valueEquals("X-Correlation-Id", good);
        assertThat(STUB.single(path1).header("X-Correlation-Id")).contains(good);

        String path2 = unique("/products");
        String minted = client.get().uri(path2).header("X-Correlation-Id", "evil value <script>").exchange()
                .expectStatus().isOk().returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");
        assertThat(UUID.fromString(minted)).isNotNull();
        assertThat(STUB.single(path2).header("X-Correlation-Id")).contains(minted);
    }

    @Test
    void webhookForwardsRawBodyUntouchedAndEnforcesSizeLimit() {
        byte[] raw = "{ \"id\":\"evt_1\",\n  \"type\" : \"payment_intent.succeeded\" }  ".getBytes(StandardCharsets.UTF_8);
        client.post().uri("/webhooks/psp")
                .header("Stripe-Signature", "t=1,v1=abc")
                .header(HttpHeaders.AUTHORIZATION, "Bearer garbage")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(raw)
                .exchange().expectStatus().isOk();
        var req = STUB.requestsTo("/webhooks/psp").getLast();
        assertThat(req.body()).isEqualTo(raw);
        assertThat(req.header("Stripe-Signature")).contains("t=1,v1=abc");
        assertThat(req.header(HttpHeaders.AUTHORIZATION)).isEmpty();

        int before = STUB.requestsTo("/webhooks/psp").size();
        client.post().uri("/webhooks/psp").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new byte[300 * 1024])
                .exchange().expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(STUB.requestsTo("/webhooks/psp")).hasSize(before);
    }

    @Test
    void unknownPathsAreNotRouted() {
        client.get().uri("/internal/anything")
                .headers(h -> h.setBearerAuth(TOKENS.valid(UUID.randomUUID().toString(), "CUSTOMER")))
                .exchange().expectStatus().isNotFound();
    }
}
