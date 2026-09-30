package com.ecom.gateway.config;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/**
 * Redis token-bucket limiters (buckets are per route + key, shared by all Gateway replicas).
 * Redis errors fail open inside {@link RedisRateLimiter}, so a Redis outage never takes the edge down.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfiguration {

    @Bean
    @Primary
    RedisRateLimiter defaultRateLimiter(GatewayProperties props) {
        return limiter(props.rateLimits().defaults());
    }

    @Bean
    RedisRateLimiter loginRateLimiter(GatewayProperties props) {
        return limiter(props.rateLimits().login());
    }

    @Bean
    RedisRateLimiter webhookRateLimiter(GatewayProperties props) {
        return limiter(props.rateLimits().webhook());
    }

    @Bean
    RemoteAddressResolver clientAddressResolver(GatewayProperties props) {
        return props.trustedProxyHops() > 0
                ? XForwardedRemoteAddressResolver.maxTrustedIndex(props.trustedProxyHops())
                : new RemoteAddressResolver() {};
    }

    /** Authenticated callers are limited per user (NAT-friendly), anonymous ones per client IP. */
    @Bean
    @Primary
    KeyResolver userOrIpKeyResolver(RemoteAddressResolver addresses) {
        return exchange -> exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(p -> "user:" + p.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(addresses, exchange)));
    }

    @Bean
    KeyResolver ipKeyResolver(RemoteAddressResolver addresses) {
        return exchange -> Mono.fromSupplier(() -> "ip:" + clientIp(addresses, exchange));
    }

    private static String clientIp(RemoteAddressResolver addresses, org.springframework.web.server.ServerWebExchange exchange) {
        InetSocketAddress address = addresses.resolve(exchange);
        if (address == null) {
            return "unknown";
        }
        return address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
    }

    private static RedisRateLimiter limiter(GatewayProperties.Limit l) {
        return new RedisRateLimiter(l.replenishRate(), l.burstCapacity(), l.requestedTokens());
    }
}
