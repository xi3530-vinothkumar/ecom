package com.ecom.platform.grpc;

import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.grpc.client.autoconfigure.GrpcClientProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GlobalClientInterceptor;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.grpc.server.GlobalServerInterceptor;

/**
 * Platform conventions on top of Spring gRPC: the server (port, health, reflection, graceful
 * shutdown) and client channels are configured under {@code spring.grpc.*}; this adds call-context
 * propagation both ways, {@link GrpcChannels} and {@link ResilientCalls}.
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.grpc.client.autoconfigure.GrpcClientAutoConfiguration",
    "org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JAutoConfiguration"
})
public class GrpcAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(GlobalServerInterceptor.class)
    static class Server {

        /** Restores CallContext and maps EcomException to gRPC status on every served call. */
        @Bean
        @GlobalServerInterceptor
        CallContextServerInterceptor callContextServerInterceptor() {
            return new CallContextServerInterceptor();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({GrpcChannelFactory.class, GrpcClientProperties.class})
    static class Client {

        @Bean
        @GlobalClientInterceptor
        CallContextClientInterceptor callContextClientInterceptor() {
            return new CallContextClientInterceptor();
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(GrpcChannelFactory.class)
        GrpcChannels grpcChannels(GrpcChannelFactory factory, GrpcClientProperties properties) {
            return new GrpcChannels(factory, properties.getChannel().keySet());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Resilience4JCircuitBreakerFactory.class)
    static class Resilience {

        @Bean
        Customizer<Resilience4JCircuitBreakerFactory> ecomCircuitBreakerDefaults() {
            return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                    .circuitBreakerConfig(ResilientCalls.CIRCUIT_BREAKER)
                    .build());
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(CircuitBreakerFactory.class)
        ResilientCalls resilientCalls(CircuitBreakerFactory<?, ?> breakers) {
            return new ResilientCalls(breakers);
        }

        /**
         * Exposes resilience4j_circuitbreaker_state etc. for the circuit-breaker-open alert. Bound here
         * because resilience4j's own metrics auto-config keys off Boot 3 class names.
         */
        @Bean
        @ConditionalOnClass(TaggedCircuitBreakerMetrics.class)
        @ConditionalOnBean(Resilience4JCircuitBreakerFactory.class)
        MeterBinder resilience4jCircuitBreakerMetrics(Resilience4JCircuitBreakerFactory factory) {
            return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(factory.getCircuitBreakerRegistry());
        }
    }
}
