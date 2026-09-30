package com.ecom.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class GatewayFiltersTest {

    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = ex -> {
        forwarded.set(ex);
        return Mono.empty();
    };

    @Test
    void sanitizerStripsAnyCaseIdentityHeadersAndMintsCorrelationId() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x")
                .header("X-User-Id", "a").header("x-user-roles", "ADMIN").header("X-USER-FOO", "b")
                .header("X-Correlation-Id", "not-a-uuid").header("Accept", "application/json"));

        new HeaderSanitizingFilter().filter(exchange, chain).block();

        HttpHeaders h = forwarded.get().getRequest().getHeaders();
        assertThat(h.keySet()).noneMatch(k -> k.toLowerCase().startsWith("x-user-"));
        assertThat(h.getFirst("Accept")).isEqualTo("application/json");
        String cid = h.getFirst("X-Correlation-Id");
        assertThat(UUID.fromString(cid)).isNotNull();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Correlation-Id")).isEqualTo(cid);
    }

    @Test
    void propagationStampsClaimsDropsAuthorizationAndFiltersOddRoles() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("user-1")
                .claim("roles", java.util.List.of("CUSTOMER", "ADMIN", "EVIL,ROLE", "lower")).build();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").header("Authorization", "Bearer t"))
                .mutate().principal(Mono.just(new JwtAuthenticationToken(jwt))).build();

        new JwtClaimsPropagationFilter().filter(exchange, chain).block();

        HttpHeaders h = forwarded.get().getRequest().getHeaders();
        assertThat(h.getFirst("X-User-Id")).isEqualTo("user-1");
        assertThat(h.getFirst("X-User-Roles")).isEqualTo("CUSTOMER,ADMIN");
        assertThat(h.containsKey(HttpHeaders.AUTHORIZATION)).isFalse();
    }

    @Test
    void propagationDropsAuthorizationForAnonymousRequests() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").header("Authorization", "Basic abc"));

        new JwtClaimsPropagationFilter().filter(exchange, chain).block();

        HttpHeaders h = forwarded.get().getRequest().getHeaders();
        assertThat(h.containsKey(HttpHeaders.AUTHORIZATION)).isFalse();
        assertThat(h.containsKey("X-User-Id")).isFalse();
    }
}
