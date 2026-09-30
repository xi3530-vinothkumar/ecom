package com.ecom.gateway.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.BooleanSpec;
import org.springframework.cloud.gateway.route.builder.Buildable;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.route.builder.UriSpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * The public route table (allow-list). Anything not listed here, notably backend
 * {@code /.well-known/**} and {@code /actuator/**}, is not reachable through the Gateway.
 * Paths are forwarded unchanged.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayRouteConfig {

    private final GatewayProperties props;
    private final RedisRateLimiter defaultLimiter;
    private final RedisRateLimiter loginLimiter;
    private final RedisRateLimiter webhookLimiter;
    private final KeyResolver userOrIp;
    private final KeyResolver ip;

    public GatewayRouteConfig(
            GatewayProperties props,
            @Qualifier("defaultRateLimiter") RedisRateLimiter defaultLimiter,
            @Qualifier("loginRateLimiter") RedisRateLimiter loginLimiter,
            @Qualifier("webhookRateLimiter") RedisRateLimiter webhookLimiter,
            @Qualifier("userOrIpKeyResolver") KeyResolver userOrIp,
            @Qualifier("ipKeyResolver") KeyResolver ip) {
        this.props = props;
        this.defaultLimiter = defaultLimiter;
        this.loginLimiter = loginLimiter;
        this.webhookLimiter = webhookLimiter;
        this.userOrIp = userOrIp;
        this.ip = ip;
    }

    @Bean
    RouteLocator ecomRoutes(RouteLocatorBuilder builder) {
        GatewayProperties.Backends b = props.backends();
        var routes = builder.routes()
                // user
                .route("user-login", r -> r.method(HttpMethod.POST).and().path("/auth/login")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(loginLimiter).setKeyResolver(ip)))
                        .uri(b.user()))
                .route("user-auth", r -> r.method(HttpMethod.POST).and().path("/auth/refresh", "/auth/logout")
                        .filters(this::defaultLimit).uri(b.user()))
                .route("user-register", r -> r.method(HttpMethod.POST).and().path("/users")
                        .filters(this::defaultLimit).uri(b.user()))
                .route("user-me", r -> to(r.path("/users/me/**"), b.user()))
                // catalog
                .route("catalog-public", r -> to(r.method(HttpMethod.GET).and().path("/products/**", "/categories/**"), b.catalog()))
                .route("catalog-admin", r -> to(r.path("/admin/products/**", "/admin/categories/**"), b.catalog()))
                // inventory
                .route("inventory-admin", r -> to(r.path("/admin/inventory/**"), b.inventory()))
                // cart / order / search
                .route("cart", r -> to(r.path("/cart/**"), b.cart()))
                .route("orders", r -> to(r.path("/orders/**"), b.order()))
                .route("search", r -> to(r.method(HttpMethod.GET).and().path("/search/**"), b.search()))
                // payment: PSP webhook, body forwarded byte-for-byte (signature is over the raw body)
                .route("payment-webhook", r -> r.method(HttpMethod.POST).and().path("/webhooks/psp")
                        .filters(f -> f
                                .filter((exchange, chain) -> {
                                    // RequestSize only checks Content-Length: refuse chunked bodies outright
                                    if (exchange.getRequest().getHeaders().getContentLength() < 0) {
                                        exchange.getResponse().setStatusCode(HttpStatus.LENGTH_REQUIRED);
                                        return exchange.getResponse().setComplete();
                                    }
                                    return chain.filter(exchange);
                                })
                                .setRequestSize(props.webhookMaxBodySize())
                                .requestRateLimiter(c -> c.setRateLimiter(webhookLimiter).setKeyResolver(ip)))
                        .uri(b.payment()));
        if (props.pspDevRoutesEnabled()) {
            routes = routes.route("payment-dev-psp", r -> to(r.path("/dev/psp/**"), b.payment()));
        }
        return routes.build();
    }

    private Buildable<Route> to(BooleanSpec predicate, URI uri) {
        return predicate.filters(this::defaultLimit).uri(uri);
    }

    private UriSpec defaultLimit(GatewayFilterSpec f) {
        return f.requestRateLimiter(c -> c.setRateLimiter(defaultLimiter).setKeyResolver(userOrIp));
    }
}
