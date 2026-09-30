package com.ecom.payment.infrastructure;

import com.ecom.inventory.proto.v1.GetReservationRequest;
import com.ecom.inventory.proto.v1.InventoryServiceGrpc;
import com.ecom.inventory.proto.v1.Reservation;
import com.ecom.order.proto.v1.GetOrderRequest;
import com.ecom.order.proto.v1.Order;
import com.ecom.order.proto.v1.OrderServiceGrpc;
import com.ecom.platform.core.EcomException;
import com.ecom.platform.core.ErrorCode;
import com.ecom.platform.grpc.GrpcChannels;
import com.ecom.platform.grpc.ResilientCalls;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Outbound gRPC to Order and Inventory. NOT_FOUND becomes empty; other failures propagate. */
@Component
public class PeerClients {

    static final String ORDER = "order";
    static final String INVENTORY = "inventory";

    private final GrpcChannels channels;
    private final ResilientCalls calls;

    public PeerClients(GrpcChannels channels, ResilientCalls calls) {
        this.channels = channels;
        this.calls = calls;
    }

    public Optional<Order> getOrder(UUID orderId) {
        var stub = OrderServiceGrpc.newBlockingStub(channels.channel(ORDER));
        var request = GetOrderRequest.newBuilder().setOrderId(orderId.toString()).build();
        return notFoundAsEmpty(() -> calls.call(ORDER, () -> stub.getOrder(request)));
    }

    public Optional<Reservation> getReservation(UUID orderId) {
        var stub = InventoryServiceGrpc.newBlockingStub(channels.channel(INVENTORY));
        var request = GetReservationRequest.newBuilder().setOrderId(orderId.toString()).build();
        return notFoundAsEmpty(() -> calls.call(INVENTORY, () -> stub.getReservation(request)));
    }

    private static <T> Optional<T> notFoundAsEmpty(java.util.function.Supplier<T> call) {
        try {
            return Optional.of(call.get());
        } catch (EcomException e) {
            if (e.code() == ErrorCode.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
