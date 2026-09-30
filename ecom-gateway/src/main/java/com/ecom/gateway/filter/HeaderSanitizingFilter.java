package com.ecom.gateway.filter;

import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.CorrelationIds;
import java.util.List;
import java.util.Locale;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * First filter on every proxied request: removes every client-supplied {@code X-User-*} header
 * (only the Gateway may assert identity downstream) and replaces the correlation id with a
 * well-formed one, echoed on the response.
 */
@Component
public class HeaderSanitizingFilter implements GlobalFilter, Ordered {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;
    private static final String IDENTITY_HEADER_PREFIX = "x-user-";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = CorrelationIds.acceptOrGenerate(
                exchange.getRequest().getHeaders().getFirst(CallContext.HEADER_CORRELATION_ID));
        ServerWebExchange sanitized = exchange.mutate()
                .request(r -> r.headers(h -> {
                    stripIdentityHeaders(h);
                    h.set(CallContext.HEADER_CORRELATION_ID, correlationId);
                }))
                .build();
        sanitized.getResponse().getHeaders().set(CallContext.HEADER_CORRELATION_ID, correlationId);
        return chain.filter(sanitized);
    }

    static void stripIdentityHeaders(HttpHeaders headers) {
        List<String> spoofed = headers.keySet().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(IDENTITY_HEADER_PREFIX))
                .toList();
        spoofed.forEach(headers::remove);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
