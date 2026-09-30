package com.ecom.payment.it;

import com.ecom.common.events.Money;
import com.ecom.inventory.proto.v1.CheckAvailabilityRequest;
import com.ecom.inventory.proto.v1.CheckAvailabilityResponse;
import com.ecom.inventory.proto.v1.GetReservationRequest;
import com.ecom.inventory.proto.v1.InventoryServiceGrpc;
import com.ecom.inventory.proto.v1.Reservation;
import com.ecom.inventory.proto.v1.ReservationStatus;
import com.ecom.order.proto.v1.GetOrderRequest;
import com.ecom.order.proto.v1.Order;
import com.ecom.order.proto.v1.OrderServiceGrpc;
import com.ecom.order.proto.v1.OrderStatus;
import com.ecom.platform.core.MoneyMapper;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** In-process fake Order + Inventory gRPC services the payment service calls during the saga. */
public class FakePeers implements AutoCloseable {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> getOrderCalls = new ConcurrentHashMap<>();
    private final Server server;
    private final ManagedChannel channel;

    public FakePeers() {
        String name = "payment-it-peers-" + UUID.randomUUID();
        try {
            server = InProcessServerBuilder.forName(name)
                    .directExecutor()
                    .addService(new OrderServiceGrpc.OrderServiceImplBase() {
                        @Override
                        public void getOrder(GetOrderRequest req, StreamObserver<Order> out) {
                            getOrderCalls.computeIfAbsent(req.getOrderId(), k -> new AtomicInteger()).incrementAndGet();
                            respond(orders.get(req.getOrderId()), out);
                        }
                    })
                    .addService(new InventoryServiceGrpc.InventoryServiceImplBase() {
                        @Override
                        public void getReservation(GetReservationRequest req, StreamObserver<Reservation> out) {
                            respond(reservations.get(req.getOrderId()), out);
                        }

                        @Override
                        public void checkAvailability(CheckAvailabilityRequest req, StreamObserver<CheckAvailabilityResponse> out) {
                            out.onError(Status.UNIMPLEMENTED.asRuntimeException());
                        }
                    })
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    private static <T> void respond(T value, StreamObserver<T> out) {
        if (value == null) {
            out.onError(Status.NOT_FOUND.withDescription("not found").asRuntimeException());
        } else {
            out.onNext(value);
            out.onCompleted();
        }
    }

    public ManagedChannel channel() {
        return channel;
    }

    /** Registers a PENDING order with a RESERVED reservation. */
    public UUID pendingOrder(String paymentMethodRef, String amount) {
        UUID orderId = UUID.randomUUID();
        putOrder(orderId, OrderStatus.PENDING, paymentMethodRef, amount);
        putReservation(orderId, ReservationStatus.RESERVED);
        return orderId;
    }

    public void putOrder(UUID orderId, OrderStatus status, String paymentMethodRef, String amount) {
        orders.put(orderId.toString(), Order.newBuilder()
                .setOrderId(orderId.toString())
                .setUserId(UUID.nameUUIDFromBytes(orderId.toString().getBytes()).toString())
                .setStatus(status)
                .setTotal(MoneyMapper.toProto(Money.of(amount, "EUR")))
                .setPaymentMethodRef(paymentMethodRef)
                .build());
    }

    public void putReservation(UUID orderId, ReservationStatus status) {
        reservations.put(orderId.toString(), Reservation.newBuilder()
                .setOrderId(orderId.toString())
                .setStatus(status)
                .build());
    }

    public String userIdOf(UUID orderId) {
        return orders.get(orderId.toString()).getUserId();
    }

    public int getOrderCalls(UUID orderId) {
        AtomicInteger c = getOrderCalls.get(orderId.toString());
        return c == null ? 0 : c.get();
    }

    @Override
    public void close() {
        channel.shutdownNow();
        server.shutdownNow();
    }
}
