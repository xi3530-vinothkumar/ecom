package com.ecom.platform.grpc;

import com.ecom.platform.core.EcomException;
import com.ecom.platform.core.ErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Wraps an outbound gRPC call with retry (transient codes only) + circuit breaker, then maps the
 * final failure to an {@link EcomException} so callers deal with one error model.
 * {@code resilientCalls.call("catalog", () -> stub.getProduct(req))}.
 */
public class ResilientCalls {

    private static final Set<Status.Code> RETRYABLE = EnumSet.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED);

    private final CircuitBreakerRegistry breakers;
    private final RetryRegistry retries;

    public ResilientCalls() {
        this.breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .recordException(ResilientCalls::isInfraFailure)
                .build());
        this.retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(100))
                .retryOnException(ResilientCalls::isInfraFailure)
                .build());
    }

    public <T> T call(String name, Supplier<T> call) {
        CircuitBreaker cb = breakers.circuitBreaker(name);
        Retry retry = retries.retry(name);
        Supplier<T> decorated = Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(cb, call));
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            throw EcomException.unavailable(name + " unavailable (circuit open)", e);
        } catch (StatusRuntimeException e) {
            throw translate(name, e);
        }
    }

    public CircuitBreakerRegistry circuitBreakers() {
        return breakers;
    }

    static EcomException translate(String name, StatusRuntimeException e) {
        ErrorCode code = ErrorCode.fromGrpc(e.getStatus().getCode());
        String description = e.getStatus().getDescription();
        return new EcomException(code, name + ": " + (description == null ? e.getStatus().getCode() : description), e);
    }

    private static boolean isInfraFailure(Throwable t) {
        return t instanceof StatusRuntimeException sre && RETRYABLE.contains(sre.getStatus().getCode());
    }
}
