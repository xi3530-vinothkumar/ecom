package com.ecom.cart.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecom.cart.proto.v1.Cart;
import com.ecom.cart.proto.v1.CartLine;
import com.ecom.cart.proto.v1.CartServiceGrpc;
import com.ecom.cart.proto.v1.GetCartRequest;
import com.ecom.cart.proto.v1.MergeCartRequest;
import com.ecom.platform.grpc.GrpcServerLifecycle;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CartGrpcIT extends AbstractCartIT {

    @Autowired GrpcServerLifecycle server;

    private ManagedChannel channel;
    private CartServiceGrpc.CartServiceBlockingStub stub;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        stub = CartServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void disconnect() {
        channel.shutdownNow();
    }

    private static Map<String, Integer> lines(Cart cart) {
        return cart.getLinesList().stream().collect(Collectors.toMap(CartLine::getSku, CartLine::getQuantity));
    }

    @Test
    void getCartReturnsRawLines() {
        String userId = userId();
        redis.opsForHash().putAll("cart:user:" + userId, Map.of("A", "2", "B", "1"));

        Cart cart = stub.getCart(GetCartRequest.newBuilder().setUserId(userId).build());

        assertThat(cart.getUserId()).isEqualTo(userId);
        assertThat(lines(cart)).containsExactlyInAnyOrderEntriesOf(Map.of("A", 2, "B", 1));
        assertThat(stub.getCart(GetCartRequest.newBuilder().setUserId(userId()).build()).getLinesList()).isEmpty();
    }

    @Test
    void mergeTakesMaxQuantityPerSkuAndDeletesGuestCart() {
        String userId = userId();
        UUID guestId = UUID.randomUUID();
        redis.opsForHash().putAll("cart:user:" + userId, Map.of("A", "2", "B", "1"));
        redis.opsForHash().putAll("cart:guest:" + guestId, Map.of("A", "1", "B", "3", "C", "4"));

        Cart merged = stub.mergeCart(MergeCartRequest.newBuilder()
                .setUserId(userId).setGuestCartId(guestId.toString()).build());

        Map<String, Integer> expected = Map.of("A", 2, "B", 3, "C", 4);
        assertThat(lines(merged)).containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(redis.hasKey("cart:guest:" + guestId)).isFalse();
        assertThat(redis.getExpire("cart:user:" + userId)).isEqualTo(-1L);
        assertThat(lines(stub.getCart(GetCartRequest.newBuilder().setUserId(userId).build())))
                .containsExactlyInAnyOrderEntriesOf(expected);

        // re-running the merge (login retry) is harmless: guest cart is gone
        Cart again = stub.mergeCart(MergeCartRequest.newBuilder()
                .setUserId(userId).setGuestCartId(guestId.toString()).build());
        assertThat(lines(again)).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void mergeIntoEmptyUserCartAdoptsGuestCart() {
        String userId = userId();
        UUID guestId = UUID.randomUUID();
        redis.opsForHash().putAll("cart:guest:" + guestId, Map.of("X", "5"));

        Cart merged = stub.mergeCart(MergeCartRequest.newBuilder()
                .setUserId(userId).setGuestCartId(guestId.toString()).build());

        assertThat(lines(merged)).containsExactly(Map.entry("X", 5));
        assertThat(redis.getExpire("cart:user:" + userId)).isEqualTo(-1L);
    }

    @Test
    void invalidArguments() {
        assertThatThrownBy(() -> stub.getCart(GetCartRequest.newBuilder().build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
        assertThatThrownBy(() -> stub.mergeCart(MergeCartRequest.newBuilder()
                        .setUserId(userId()).setGuestCartId("bad").build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }
}
