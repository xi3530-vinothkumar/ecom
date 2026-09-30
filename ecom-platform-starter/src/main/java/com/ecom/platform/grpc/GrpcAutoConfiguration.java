package com.ecom.platform.grpc;

import io.grpc.BindableService;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ClassUtils;

@AutoConfiguration
@ConditionalOnClass(name = "io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder")
@EnableConfigurationProperties(GrpcProperties.class)
public class GrpcAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "ecom.grpc.server", name = "enabled", matchIfMissing = true)
    GrpcServerLifecycle grpcServerLifecycle(GrpcProperties props, ObjectProvider<BindableService> services) {
        List<BindableService> list = services.orderedStream().toList();
        return new GrpcServerLifecycle(props.getServer(), list);
    }

    @Bean
    @ConditionalOnMissingBean
    GrpcChannels grpcChannels(GrpcProperties props) {
        return new GrpcChannels(props);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "io.github.resilience4j.circuitbreaker.CircuitBreaker")
    ResilientCalls resilientCalls(ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters) {
        ResilientCalls calls = new ResilientCalls();
        // exposes resilience4j_circuitbreaker_state etc. for the circuit-breaker-open alert
        if (ClassUtils.isPresent("io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics", null)) {
            meters.ifAvailable(registry -> io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics
                    .ofCircuitBreakerRegistry(calls.circuitBreakers())
                    .bindTo(registry));
        }
        return calls;
    }
}
