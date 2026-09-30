package com.ecom.platform.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecom.catalog.proto.v1.CatalogServiceGrpc;
import com.ecom.catalog.proto.v1.GetProductRequest;
import com.ecom.catalog.proto.v1.ProductItem;
import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.EcomException;
import com.ecom.platform.core.ErrorCode;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrpcPropagationTest {

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
                    .build());
            out.onCompleted();
        }
    }

    GrpcServerLifecycle server;
    GrpcChannels channels;

    @BeforeEach
    void start() {
        var serverProps = new GrpcProperties.Server();
        serverProps.setPort(0);
        server = new GrpcServerLifecycle(serverProps, List.of(new EchoCatalog()));
        server.start();
        var props = new GrpcProperties();
        var client = new GrpcProperties.Client();
        client.setAddress("localhost:" + server.getPort());
        props.getClients().put("catalog", client);
        channels = new GrpcChannels(props);
    }

    @AfterEach
    void stop() {
        channels.destroy();
        server.stop();
        CallContext.clear();
    }

    @Test
    void propagatesCorrelationIdAndIdentity() {
        String corr = "0b6f1f9e-2c55-4a4b-9a55-1d0e5b1a7c11";
        CallContext.set(new CallContext(corr, "user-42", Set.of("ADMIN")));
        var stub = CatalogServiceGrpc.newBlockingStub(channels.channel("catalog"));

        var item = new ResilientCalls().call("catalog", () -> stub.getProduct(GetProductRequest.newBuilder().setSku("S1").build()));

        assertThat(item.getName()).isEqualTo("user-42|ADMIN|" + corr);
    }

    @Test
    void mapsBusinessExceptionToStatusAndBack() {
        var stub = CatalogServiceGrpc.newBlockingStub(channels.channel("catalog"));

        assertThatThrownBy(() -> new ResilientCalls()
                        .call("catalog", () -> stub.getProduct(GetProductRequest.newBuilder().setSku("missing").build())))
                .isInstanceOfSatisfying(EcomException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void unreachablePeerBecomesUnavailable() {
        var props = new GrpcProperties();
        var client = new GrpcProperties.Client();
        client.setAddress("localhost:1");
        props.getClients().put("dead", client);
        var stub = CatalogServiceGrpc.newBlockingStub(new GrpcChannels(props).channel("dead"));

        assertThatThrownBy(() -> new ResilientCalls()
                        .call("dead", () -> stub.getProduct(GetProductRequest.newBuilder().setSku("x").build())))
                .isInstanceOfSatisfying(EcomException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAVAILABLE));
    }
}
