package com.ecom.gateway.security;

import com.ecom.gateway.config.GatewayProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

/**
 * Stateless JWT resource server. Tokens are RS256, verified against the User service JWKS
 * (cached; unknown {@code kid} triggers a refetch), with iss/aud/exp validation.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfiguration {

    /**
     * Credential endpoints and the PSP webhook never evaluate a bearer token: a stale access
     * token sent by a browser must not block login/refresh with a 401.
     */
    private static final ServerWebExchangeMatcher BEARER_IGNORED = new OrServerWebExchangeMatcher(
            new PathPatternParserServerWebExchangeMatcher("/auth/**"),
            new PathPatternParserServerWebExchangeMatcher("/users", HttpMethod.POST),
            new PathPatternParserServerWebExchangeMatcher("/webhooks/**"));

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http, GatewayProperties props, ReactiveJwtDecoder jwtDecoder) {
        ServerBearerTokenAuthenticationConverter bearer = new ServerBearerTokenAuthenticationConverter();
        ServerAuthenticationConverter bearerUnlessIgnored = exchange -> BEARER_IGNORED.matches(exchange)
                .flatMap(m -> m.isMatch() ? Mono.empty() : bearer.convert(exchange));

        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(c -> c.disable())
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .cors(c -> c.configurationSource(corsConfigurationSource(props)))
                .authorizeExchange(ex -> {
                    ex.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                            .permitAll()
                            .pathMatchers(HttpMethod.GET, "/products/**", "/categories/**", "/search/**").permitAll()
                            .pathMatchers(HttpMethod.POST, "/auth/login", "/auth/refresh", "/auth/logout", "/users",
                                    "/webhooks/psp")
                            .permitAll()
                            // guest carts; a valid bearer token is still authenticated and propagated
                            .pathMatchers("/cart/**").permitAll();
                    if (props.pspDevRoutesEnabled()) {
                        ex.pathMatchers("/dev/psp/**").permitAll();
                    }
                    ex.pathMatchers("/admin/**").hasRole("ADMIN").anyExchange().authenticated();
                })
                .oauth2ResourceServer(o -> o
                        .bearerTokenConverter(bearerUnlessIgnored)
                        .jwt(j -> j.jwtDecoder(jwtDecoder).jwtAuthenticationConverter(authenticationConverter())));
        return http.build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(GatewayProperties props) {
        GatewayProperties.Jwt cfg = props.jwt();
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(cfg.jwksUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(cfg.audience()));
        OAuth2TokenValidator<Jwt> subject = new JwtClaimValidator<String>(
                JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank());
        OAuth2TokenValidator<Jwt> expiry = new JwtClaimValidator<Object>(JwtClaimNames.EXP, exp -> exp != null);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(cfg.issuer()), audience, subject, expiry));
        return decoder;
    }

    static Converter<Jwt, Mono<AbstractAuthenticationToken>> authenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

    private static CorsConfigurationSource corsConfigurationSource(GatewayProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "X-Correlation-Id", "X-Guest-Cart-Id", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("X-Correlation-Id", "Location", "Retry-After",
                "X-RateLimit-Remaining", "X-RateLimit-Replenish-Rate", "X-RateLimit-Burst-Capacity"));
        // refresh-token cookie on /auth/** needs credentialed CORS
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
