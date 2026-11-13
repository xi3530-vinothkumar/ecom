package com.ecom.platform.grpc;

import com.ecom.platform.core.EcomException;
import com.ecom.platform.core.ErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

/**
 * Wraps an outbound gRPC call with retry (transient codes only, Spring Framework {@link RetryTemplate})
 * around a Spring Cloud circuit breaker (Resilience4j), then maps the final failure to an
 * {@link EcomException} so callers deal with one error model.
 * {@code resilientCalls.call("catalog", () -> stub.getProduct(req))}.
 */
public class ResilientCalls {

    private static final Set<Status.Code> RETRYABLE = EnumSet.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED);

    /** Only infrastructure failures count against the breaker; business errors (NOT_FOUND...) never open it. */
    static final CircuitBreakerConfig CIRCUIT_BREAKER = CircuitBreakerConfig.custom()
            .slidingWindowSize(20)
            .minimumNumberOfCalls(10)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .recordException(ResilientCalls::isInfraFailure)
            .build();

    /** 3 attempts, fixed 100ms apart; an open circuit is not retried. */
    static final RetryPolicy RETRY = RetryPolicy.builder()
            .maxRetries(2)
            .delay(Duration.ofMillis(100))
            .multiplier(1.0)
            .predicate(ResilientCalls::isInfraFailure)
            .build();

    private final CircuitBreakerFactory<?, ?> breakers;
    private final RetryTemplate retry = new RetryTemplate(RETRY);

    public ResilientCalls(CircuitBreakerFactory<?, ?> breakers) {
        this.breakers = breakers;
    }

    public <T> T call(String name, Supplier<T> call) {
        var breaker = breakers.create(name);
        try {
            return retry.invoke(() -> breaker.run(call, ResilientCalls::rethrow));
        } catch (CallNotPermittedException e) {
            throw EcomException.unavailable(name + " unavailable (circuit open)", e);
        } catch (StatusRuntimeException e) {
            throw translate(name, e);
        }
    }

    static EcomException translate(String name, StatusRuntimeException e) {
        ErrorCode code = ErrorCode.fromGrpc(e.getStatus().getCode());
        String description = e.getStatus().getDescription();
        return new EcomException(code, name + ": " + (description == null ? e.getStatus().getCode() : description), e);
    }

    private static boolean isInfraFailure(Throwable t) {
        return t instanceof StatusRuntimeException sre && RETRYABLE.contains(sre.getStatus().getCode());
    }

    /** Fallback that surfaces the original failure instead of Spring Cloud's NoFallbackAvailableException. */
    private static <T> T rethrow(Throwable t) {
        if ((t instanceof ExecutionException || t instanceof CompletionException) && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof RuntimeException e) {
            throw e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        throw new IllegalStateException(t);
    }
}
