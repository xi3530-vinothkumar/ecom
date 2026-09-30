package com.ecom.order.it;

import com.ecom.cart.proto.v1.Cart;
import com.ecom.cart.proto.v1.CartLine;
import com.ecom.cart.proto.v1.CartServiceGrpc;
import com.ecom.cart.proto.v1.GetCartRequest;
import com.ecom.catalog.proto.v1.CatalogServiceGrpc;
import com.ecom.catalog.proto.v1.GetProductsBatchRequest;
import com.ecom.catalog.proto.v1.GetProductsBatchResponse;
import com.ecom.catalog.proto.v1.ProductItem;
import com.ecom.common.events.Money;
import com.ecom.payment.proto.v1.GetTransactionRequest;
import com.ecom.payment.proto.v1.PaymentServiceGrpc;
import com.ecom.payment.proto.v1.PaymentTransaction;
import com.ecom.platform.core.MoneyMapper;
import com.ecom.user.proto.v1.Address;
import com.ecom.user.proto.v1.GetAddressRequest;
import com.ecom.user.proto.v1.UserServiceGrpc;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Programmable fake Cart/Catalog/User/Payment gRPC servers, each on its own random port. */
final class FakePeers {

    static final Map<String, List<CartLine>> carts = new ConcurrentHashMap<>();
    static final Map<String, ProductItem> products = new ConcurrentHashMap<>();
    static final Map<String, Address> addresses = new ConcurrentHashMap<>();
    static final Map<String, PaymentTransaction> transactions = new ConcurrentHashMap<>();

    static final Server cart = start(new CartServiceGrpc.CartServiceImplBase() {
        @Override
        public void getCart(GetCartRequest req, StreamObserver<Cart> out) {
            out.onNext(Cart.newBuilder().setUserId(req.getUserId())
                    .addAllLines(carts.getOrDefault(req.getUserId(), List.of())).build());
            out.onCompleted();
        }
    });

    static final Server catalog = start(new CatalogServiceGrpc.CatalogServiceImplBase() {
        @Override
        public void getProductsBatch(GetProductsBatchRequest req, StreamObserver<GetProductsBatchResponse> out) {
            var b = GetProductsBatchResponse.newBuilder();
            req.getSkusList().stream().map(products::get).filter(p -> p != null).forEach(b::addItems);
            out.onNext(b.build());
            out.onCompleted();
        }
    });

    static final Server user = start(new UserServiceGrpc.UserServiceImplBase() {
        @Override
        public void getAddress(GetAddressRequest req, StreamObserver<Address> out) {
            Address a = addresses.get(req.getUserId() + "/" + req.getAddressId());
            if (a == null) {
                out.onError(Status.NOT_FOUND.withDescription("address not found").asRuntimeException());
                return;
            }
            out.onNext(a);
            out.onCompleted();
        }
    });

    static final Server payment = start(new PaymentServiceGrpc.PaymentServiceImplBase() {
        @Override
        public void getTransaction(GetTransactionRequest req, StreamObserver<PaymentTransaction> out) {
            PaymentTransaction t = transactions.get(req.getOrderId());
            if (t == null) {
                out.onError(Status.NOT_FOUND.withDescription("no transaction").asRuntimeException());
                return;
            }
            out.onNext(t);
            out.onCompleted();
        }
    });

    static String address(Server server) {
        return "localhost:" + server.getPort();
    }

    static void product(String sku, String name, String price, boolean active) {
        products.put(sku, ProductItem.newBuilder()
                .setSku(sku)
                .setProductId("p-" + sku)
                .setName(name)
                .setPrice(MoneyMapper.toProto(Money.of(price, "EUR")))
                .setActive(active)
                .build());
    }

    static void cart(String userId, Object... skuQty) {
        var lines = new java.util.ArrayList<CartLine>();
        for (int i = 0; i < skuQty.length; i += 2) {
            lines.add(CartLine.newBuilder().setSku((String) skuQty[i]).setQuantity((Integer) skuQty[i + 1]).build());
        }
        carts.put(userId, lines);
    }

    static void address(String userId, String addressId) {
        addresses.put(userId + "/" + addressId, Address.newBuilder()
                .setAddressId(addressId)
                .setUserId(userId)
                .setLine1("Damrak 1")
                .setCity("Amsterdam")
                .setPostalCode("1012LG")
                .setCountryCode("NL")
                .build());
    }

    private static Server start(BindableService service) {
        try {
            Server s = NettyServerBuilder.forPort(0).addService(service).build().start();
            Runtime.getRuntime().addShutdownHook(new Thread(s::shutdownNow));
            return s;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private FakePeers() {}
}
