package com.ecom.platform.core;

import java.util.Optional;
import java.util.Set;
import org.slf4j.MDC;

/**
 * Per-call identity + correlation, bound for the duration of a request by the web filter (from
 * Gateway-stamped headers), the gRPC server interceptor (from metadata) or the Kafka consumer (from
 * the envelope), and re-emitted by the gRPC client interceptor. Held in a {@link ScopedValue}, so it
 * is immutable within a scope, cheap on virtual threads and never leaks past the call; nested
 * {@link #run}/{@link #call} rebind it and restore the outer value on exit. Also mirrored into the
 * logging MDC for the same scope.
 */
public record CallContext(String correlationId, String userId, Set<String> roles) {

    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ROLES = "X-User-Roles";
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_USER_ID = "userId";
    public static final String ROLE_ADMIN = "ADMIN";

    private static final ScopedValue<CallContext> CURRENT = ScopedValue.newInstance();

    public CallContext {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public static Optional<CallContext> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /** Runs {@code op} with {@code ctx} as the current context. */
    public static void run(CallContext ctx, Runnable op) {
        call(ctx, () -> {
            op.run();
            return null;
        });
    }

    /** Calls {@code op} with {@code ctx} as the current context, propagating its result and exception. */
    public static <R, X extends Throwable> R call(CallContext ctx, ScopedValue.CallableOp<? extends R, X> op) throws X {
        return ScopedValue.where(CURRENT, ctx).call(() -> withMdc(ctx, op));
    }

    private static <R, X extends Throwable> R withMdc(CallContext ctx, ScopedValue.CallableOp<? extends R, X> op) throws X {
        String outerCorrelationId = MDC.get(MDC_CORRELATION_ID);
        String outerUserId = MDC.get(MDC_USER_ID);
        putOrRemove(MDC_CORRELATION_ID, ctx.correlationId());
        putOrRemove(MDC_USER_ID, ctx.userId());
        try {
            return op.call();
        } finally {
            putOrRemove(MDC_CORRELATION_ID, outerCorrelationId);
            putOrRemove(MDC_USER_ID, outerUserId);
        }
    }

    private static void putOrRemove(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
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
