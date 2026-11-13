package com.ecom.user.infrastructure;

import com.ecom.cart.proto.v1.CartServiceGrpc;
import com.ecom.cart.proto.v1.MergeCartRequest;
import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.CorrelationIds;
import com.ecom.platform.grpc.GrpcChannels;
import com.ecom.platform.grpc.ResilientCalls;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Outbound CartService.MergeCart (guest cart -> user cart at login). */
@Component
public class CartMergeClient {

    private static final String CART = "cart";

    private final GrpcChannels channels;
    private final ResilientCalls calls;
    private volatile CartServiceGrpc.CartServiceBlockingStub stub;

    public CartMergeClient(GrpcChannels channels, ResilientCalls calls) {
        this.channels = channels;
        this.calls = calls;
    }

    /**
     * The login request is anonymous at the edge, so the call context is switched to the freshly
     * authenticated user for the duration of the call: Cart may authorize on the propagated identity.
     */
    public void merge(String guestCartId, UUID userId, Set<String> roles) {
        String correlationId = CorrelationIds.acceptOrGenerate(CallContext.correlationIdOrNull());
        MergeCartRequest request = MergeCartRequest.newBuilder()
                .setGuestCartId(guestCartId)
                .setUserId(userId.toString())
                .build();
        // nested scope: the outer (anonymous) context is restored automatically afterwards
        CallContext.run(new CallContext(correlationId, userId.toString(), roles),
                () -> calls.call(CART, () -> stub().mergeCart(request)));
    }

    private CartServiceGrpc.CartServiceBlockingStub stub() {
        CartServiceGrpc.CartServiceBlockingStub s = stub;
        if (s == null) {
            s = CartServiceGrpc.newBlockingStub(channels.channel(CART));
            stub = s;
        }
        return s;
    }
}
