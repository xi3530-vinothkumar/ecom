package com.ecom.order.infrastructure;

import com.ecom.cart.proto.v1.Cart;
import com.ecom.cart.proto.v1.CartServiceGrpc;
import com.ecom.cart.proto.v1.GetCartRequest;
import com.ecom.catalog.proto.v1.CatalogServiceGrpc;
import com.ecom.catalog.proto.v1.GetProductsBatchRequest;
import com.ecom.catalog.proto.v1.ProductItem;
import com.ecom.payment.proto.v1.GetTransactionRequest;
import com.ecom.payment.proto.v1.PaymentServiceGrpc;
import com.ecom.payment.proto.v1.PaymentTransaction;
import com.ecom.platform.grpc.GrpcChannels;
import com.ecom.platform.grpc.ResilientCalls;
import com.ecom.user.proto.v1.Address;
import com.ecom.user.proto.v1.GetAddressRequest;
import com.ecom.user.proto.v1.UserServiceGrpc;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

/** Outbound gRPC calls; every call goes through retry + circuit breaker and fails as EcomException. */
@Component
public class PeerClients {

    private static final String CART = "cart";
    private static final String CATALOG = "catalog";
    private static final String USER = "user";
    private static final String PAYMENT = "payment";

    private final ResilientCalls calls;
    private final CartServiceGrpc.CartServiceBlockingStub cart;
    private final CatalogServiceGrpc.CatalogServiceBlockingStub catalog;
    private final UserServiceGrpc.UserServiceBlockingStub user;
    private final PaymentServiceGrpc.PaymentServiceBlockingStub payment;

    public PeerClients(GrpcChannels channels, ResilientCalls calls) {
        this.calls = calls;
        this.cart = CartServiceGrpc.newBlockingStub(channels.channel(CART));
        this.catalog = CatalogServiceGrpc.newBlockingStub(channels.channel(CATALOG));
        this.user = UserServiceGrpc.newBlockingStub(channels.channel(USER));
        this.payment = PaymentServiceGrpc.newBlockingStub(channels.channel(PAYMENT));
    }

    public Cart getCart(String userId) {
        return calls.call(CART, () -> cart.getCart(GetCartRequest.newBuilder().setUserId(userId).build()));
    }

    public List<ProductItem> getProducts(Collection<String> skus) {
        return calls.call(CATALOG, () -> catalog.getProductsBatch(
                        GetProductsBatchRequest.newBuilder().addAllSkus(skus).build()))
                .getItemsList();
    }

    public Address getAddress(String userId, String addressId) {
        return calls.call(USER, () -> user.getAddress(
                GetAddressRequest.newBuilder().setUserId(userId).setAddressId(addressId).build()));
    }

    public PaymentTransaction getTransaction(String orderId) {
        return calls.call(PAYMENT, () -> payment.getTransaction(
                GetTransactionRequest.newBuilder().setOrderId(orderId).build()));
    }
}
