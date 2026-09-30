package com.ecom.platform.core;

import java.util.Optional;
import java.util.Set;
import org.slf4j.MDC;

/**
 * Per-call identity + correlation, populated by the web filter (from Gateway-stamped headers),
 * the gRPC server interceptor (from metadata) or the Kafka consumer (from the envelope), and
 * re-emitted by the gRPC client interceptor. Also mirrored into the logging MDC.
 */
public record CallContext(String correlationId, String userId, Set<String> roles) {

    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ROLES = "X-User-Roles";
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_USER_ID = "userId";
    public static final String ROLE_ADMIN = "ADMIN";

    private static final ThreadLocal<CallContext> CURRENT = new ThreadLocal<>();

    public CallContext {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public static Optional<CallContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void set(CallContext ctx) {
        CURRENT.set(ctx);
        if (ctx.correlationId() != null) {
            MDC.put(MDC_CORRELATION_ID, ctx.correlationId());
        }
        if (ctx.userId() != null) {
            MDC.put(MDC_USER_ID, ctx.userId());
        }
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_CORRELATION_ID);
        MDC.remove(MDC_USER_ID);
    }

    public static String correlationIdOrNull() {
        return current().map(CallContext::correlationId).orElse(null);
    }

    /** Authenticated user id, or 401. */
    public static String requireUserId() {
        return current().map(CallContext::userId)
                .filter(id -> !id.isBlank())
                .orElseThrow(() -> EcomException.unauthenticated("Authentication required"));
    }

    /** 401 when anonymous, 403 when authenticated without the role. */
    public static void requireRole(String role) {
        requireUserId();
        if (!current().map(c -> c.roles().contains(role)).orElse(false)) {
            throw EcomException.forbidden("Requires role " + role);
        }
    }

    public static Set<String> parseRoles(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        return Set.of(header.trim().split("\\s*,\\s*"));
    }
}
