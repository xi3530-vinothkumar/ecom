package com.ecom.platform.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecom.catalog.proto.v1.CatalogServiceGrpc;
import com.ecom.catalog.proto.v1.GetProductRequest;
import com.ecom.catalog.proto.v1.ProductItem;
import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.EcomException;
import com.ecom.platform.core.ErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

/** Full Spring gRPC wiring: Netty server, channel factory, platform interceptors and circuit breaker. */
@SpringBootTest(
        classes = GrpcPropagationTest.App.class,
        properties = {
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "spring.grpc.server.enabled=true",
            "spring.grpc.client.channel.dead.target=static://localhost:1",
        })
class GrpcPropagationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        EchoCatalog echoCatalog() {
            return new EchoCatalog();
        }
    }

    /** Echoes the propagated identity back through the product name. */
    static class EchoCatalog extends CatalogServiceGrpc.CatalogServiceImplBase {
        @Override
        public void getProduct(GetProductRequest req, StreamObserver<ProductItem> out) {
            if (req.getSku().equals("missing")) {
                throw EcomException.notFound("no such sku");
            }
            CallContext ctx = CallContext.current().orElseThrow();
            out.onNext(ProductItem.newBuilder()
                    .setSku(req.getSku())
                    .setName(ctx.userId() + "|" + String.join(",", ctx.roles()) + "|" + ctx.correlationId())
                    .setVariantName(Thread.currentThread().isVirtual() ? "virtual" : "platform")
                    .build());
            out.onCompleted();
        }
    }

    static final int PORT = TestSocketUtils.findAvailableTcpPort();

    @DynamicPropertySource
    static void grpc(DynamicPropertyRegistry r) {
        r.add("spring.grpc.server.port", () -> PORT);
        r.add("spring.grpc.client.channel.catalog.target", () -> "static://localhost:" + PORT);
    }

    @Autowired GrpcChannels channels;
    @Autowired ResilientCalls calls;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired MeterRegistry meters;

    @Test
    void propagatesCorrelationIdAndIdentity() {
        String corr = "0b6f1f9e-2c55-4a4b-9a55-1d0e5b1a7c11";
        var stub = CatalogServiceGrpc.newBlockingStub(channels.channel("catalog"));

        var item = CallContext.call(new CallContext(corr, "user-42", Set.of("ADMIN")),
                () -> calls.call("catalog", () -> stub.getProduct(GetProductRequest.newBuilder().setSku("S1").build())));

        assertThat(item.getName()).isEqualTo("user-42|ADMIN|" + corr);
        assertThat(item.getVariantName()).as("served on a virtual thread").isEqualTo("virtual");
    }

    @Test
    void mapsBusinessExceptionToStatusAndBack() {
        var stub = CatalogServiceGrpc.newBlockingStub(channels.channel("catalog"));

        assertThatThrownBy(() -> calls.call("catalog", () -> stub.getProduct(GetProductRequest.newBuilder().setSku("missing").build())))
                .isInstanceOfSatisfying(EcomException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void unreachablePeerBecomesUnavailable() {
        var stub = CatalogServiceGrpc.newBlockingStub(channels.channel("dead"));

        assertThatThrownBy(() -> calls.call("dead", () -> stub.getProduct(GetProductRequest.newBuilder().setSku("x").build())))
                .isInstanceOfSatisfying(EcomException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAVAILABLE));
        // every attempt went through the breaker with the platform config, and the metrics binder saw it
        var breaker = breakers.circuitBreaker("dead");
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
        assertThat(breaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(20);
        assertThat(meters.find("resilience4j.circuitbreaker.state").tag("name", "dead").gauges()).isNotEmpty();
    }

    @Test
    void unknownChannelFailsFast() {
        assertThatThrownBy(() -> channels.channel("nope"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.grpc.client.channel.nope.target");
    }
}
