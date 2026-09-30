package com.ecom.cart.it;

import com.ecom.catalog.proto.v1.CatalogServiceGrpc;
import com.ecom.catalog.proto.v1.GetProductsBatchRequest;
import com.ecom.catalog.proto.v1.GetProductsBatchResponse;
import com.ecom.catalog.proto.v1.ProductItem;
import com.ecom.common.proto.v1.Money;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Real-port stand-in for Catalog's GetProductsBatch. */
final class FakeCatalog extends CatalogServiceGrpc.CatalogServiceImplBase {

    final Map<String, ProductItem> products = new ConcurrentHashMap<>();
    final AtomicInteger batchCalls = new AtomicInteger();
    private final Server server;

    FakeCatalog() {
        try {
            server = NettyServerBuilder.forPort(0).addService(this).build().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdownNow));
    }

    int port() {
        return server.getPort();
    }

    void add(String sku, String name, long units, int nanos, boolean active) {
        products.put(sku, ProductItem.newBuilder()
                .setSku(sku)
                .setProductId("p-" + sku)
                .setName(name)
                .setVariantName("default")
                .setPrice(Money.newBuilder().setCurrencyCode("EUR").setUnits(units).setNanos(nanos))
                .setActive(active)
                .build());
    }

    @Override
    public void getProductsBatch(GetProductsBatchRequest request, StreamObserver<GetProductsBatchResponse> out) {
        batchCalls.incrementAndGet();
        var response = GetProductsBatchResponse.newBuilder();
        request.getSkusList().stream().map(products::get).filter(p -> p != null).forEach(response::addItems);
        out.onNext(response.build());
        out.onCompleted();
    }
}
