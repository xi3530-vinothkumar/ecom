package com.ecom.cart.grpc;

import com.ecom.cart.application.CartService;
import com.ecom.cart.domain.CartOwner;
import com.ecom.cart.proto.v1.Cart;
import com.ecom.cart.proto.v1.CartLine;
import com.ecom.cart.proto.v1.CartServiceGrpc;
import com.ecom.cart.proto.v1.GetCartRequest;
import com.ecom.cart.proto.v1.MergeCartRequest;
import com.ecom.platform.core.EcomException;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CartGrpcService extends CartServiceGrpc.CartServiceImplBase {

    private final CartService carts;

    public CartGrpcService(CartService carts) {
        this.carts = carts;
    }

    @Override
    public void getCart(GetCartRequest request, StreamObserver<Cart> out) {
        CartOwner.User user = user(request.getUserId());
        out.onNext(toProto(user, carts.rawLines(user)));
        out.onCompleted();
    }

    @Override
    public void mergeCart(MergeCartRequest request, StreamObserver<Cart> out) {
        CartOwner.User user = user(request.getUserId());
        CartOwner.Guest guest;
        try {
            guest = new CartOwner.Guest(UUID.fromString(request.getGuestCartId()));
        } catch (IllegalArgumentException _) {
            throw EcomException.validation("guest_cart_id must be a UUID");
        }
        out.onNext(toProto(user, carts.merge(guest, user)));
        out.onCompleted();
    }

    private static CartOwner.User user(String userId) {
        if (userId == null || userId.isBlank()) {
            throw EcomException.validation("user_id is required");
        }
        return new CartOwner.User(userId);
    }

    private static Cart toProto(CartOwner.User user, Map<String, Integer> lines) {
        var cart = Cart.newBuilder().setUserId(user.userId());
        lines.forEach((sku, qty) -> cart.addLines(CartLine.newBuilder().setSku(sku).setQuantity(qty)));
        return cart.build();
    }
}
