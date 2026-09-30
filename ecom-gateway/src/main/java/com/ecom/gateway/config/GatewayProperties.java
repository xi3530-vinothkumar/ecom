package com.ecom.gateway.config;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("ecom.gateway")
public record GatewayProperties(
        Backends backends,
        Jwt jwt,
        @DefaultValue("http://localhost:3000") List<String> corsAllowedOrigins,
        @DefaultValue("false") boolean pspDevRoutesEnabled,
        /** Number of trusted reverse proxies (e.g. ALB = 1) in front of the Gateway for X-Forwarded-For. 0 = use the socket peer. */
        @DefaultValue("0") int trustedProxyHops,
        @DefaultValue("256KB") DataSize webhookMaxBodySize,
        RateLimits rateLimits) {

    public record Backends(URI user, URI catalog, URI inventory, URI cart, URI order, URI payment, URI search) {}

    public record Jwt(URI jwksUri, String issuer, String audience) {}

    /** Token bucket: {@code replenishRate} tokens/s, bucket size {@code burstCapacity}, cost per request {@code requestedTokens}. */
    public record Limit(int replenishRate, int burstCapacity, @DefaultValue("1") int requestedTokens) {}

    /** Values live in application.yml (env-overridable there). */
    public record RateLimits(Limit defaults, Limit login, Limit webhook) {}
}
