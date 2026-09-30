package com.ecom.platform.web;

import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.CorrelationIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the identity headers stamped by the Gateway. These are trusted ONLY because NetworkPolicy
 * makes the Gateway the sole ingress to service HTTP ports, and the Gateway strips client-supplied
 * copies before stamping its own.
 */
public class CallContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = CorrelationIds.acceptOrGenerate(request.getHeader(CallContext.HEADER_CORRELATION_ID));
        String userId = request.getHeader(CallContext.HEADER_USER_ID);
        CallContext.set(new CallContext(
                correlationId,
                userId == null || userId.isBlank() ? null : userId,
                CallContext.parseRoles(request.getHeader(CallContext.HEADER_USER_ROLES))));
        response.setHeader(CallContext.HEADER_CORRELATION_ID, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            CallContext.clear();
        }
    }
}
