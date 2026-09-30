package com.ecom.gateway.filter;

import com.ecom.platform.core.CallContext;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Runs after Spring Security validated the bearer token: stamps {@code X-User-Id} /
 * {@code X-User-Roles} from the verified claims and always drops {@code Authorization}, so the
 * raw JWT never reaches a backend.
 */
@Component
public class JwtClaimsPropagationFilter implements GlobalFilter, Ordered {

    /** Roles become a comma-separated header: anything but plain identifiers is dropped. */
    private static final Pattern ROLE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(p -> ((JwtAuthenticationToken) p).getToken())
                .map(jwt -> withIdentity(exchange, jwt))
                .defaultIfEmpty(withoutAuthorization(exchange))
                .flatMap(chain::filter);
    }

    private static ServerWebExchange withIdentity(ServerWebExchange exchange, Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        String joined = roles == null ? "" : String.join(",", roles.stream().filter(r -> ROLE.matcher(r).matches()).toList());
        return exchange.mutate().request(r -> r.headers(h -> {
                    h.remove(HttpHeaders.AUTHORIZATION);
                    h.set(CallContext.HEADER_USER_ID, jwt.getSubject());
                    if (!joined.isEmpty()) {
                        h.set(CallContext.HEADER_USER_ROLES, joined);
                    }
                }))
                .build();
    }

    private static ServerWebExchange withoutAuthorization(ServerWebExchange exchange) {
        return exchange.mutate().request(r -> r.headers(h -> h.remove(HttpHeaders.AUTHORIZATION))).build();
    }

    @Override
    public int getOrder() {
        return HeaderSanitizingFilter.ORDER + 1;
    }
}
