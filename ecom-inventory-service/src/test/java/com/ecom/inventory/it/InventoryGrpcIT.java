package com.ecom.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.payload.OrderCreatedPayload;
import com.ecom.inventory.infrastructure.OrderEventsListener;
import com.ecom.inventory.proto.v1.CheckAvailabilityRequest;
import com.ecom.inventory.proto.v1.GetReservationRequest;
import com.ecom.inventory.proto.v1.InventoryServiceGrpc;
import com.ecom.inventory.proto.v1.ReservationStatus;
import com.ecom.inventory.proto.v1.SkuQuantity;
import com.ecom.platform.grpc.GrpcServerLifecycle;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class InventoryGrpcIT extends AbstractInventoryIT {

    @Autowired GrpcServerLifecycle server;
    @Autowired OrderEventsListener listener;

    private ManagedChannel channel;
    private InventoryServiceGrpc.InventoryServiceBlockingStub stub;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        stub = InventoryServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void disconnect() {
        channel.shutdownNow();
    }

    private static SkuQuantity item(String sku, int qty) {
        return SkuQuantity.newBuilder().setSku(sku).setQuantity(qty).build();
    }

    @Test
    void checkAvailabilityReportsPerSkuAndOverall() {
        String a = sku("A"), b = sku("B"), unknown = sku("U");
        stock(a, 5);
        stock(b, 1);

        var ok = stub.checkAvailability(CheckAvailabilityRequest.newBuilder()
                .addItems(item(a, 2)).addItems(item(a, 3)).addItems(item(b, 1)).build());
        assertThat(ok.getAllAvailable()).isTrue();
        assertThat(ok.getItemsList()).hasSize(2);

        var notOk = stub.checkAvailability(CheckAvailabilityRequest.newBuilder()
                .addItems(item(a, 6)).addItems(item(unknown, 1)).build());
        assertThat(notOk.getAllAvailable()).isFalse();
        assertThat(notOk.getItemsList()).allSatisfy(i -> assertThat(i.getSufficient()).isFalse());
        assertThat(notOk.getItemsList()).filteredOn(i -> i.getSku().equals(a))
                .singleElement().satisfies(i -> assertThat(i.getAvailable()).isEqualTo(5));
    }

    @Test
    void invalidRequestIsInvalidArgument() {
        assertThatThrownBy(() -> stub.checkAvailability(
                        CheckAvailabilityRequest.newBuilder().addItems(item("A", 0)).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
        assertThatThrownBy(() -> stub.getReservation(GetReservationRequest.newBuilder().setOrderId("nope").build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void getReservationReturnsAggregateOrNotFound() {
        assertThatThrownBy(() -> stub.getReservation(
                        GetReservationRequest.newBuilder().setOrderId(UUID.randomUUID().toString()).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));

        String a = sku("A"), b = sku("B");
        stock(a, 5);
        stock(b, 5);
        UUID orderId = UUID.randomUUID();
        listener.onOrderCreated(record(EventTopics.ORDER_CREATED, orderId.toString(),
                new OrderCreatedPayload(orderId.toString(), "u", lines(a, 1, b, 2))));

        var r = stub.getReservation(GetReservationRequest.newBuilder().setOrderId(orderId.toString()).build());
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(r.getLinesList()).hasSize(2);
        assertThat(Instant.ofEpochSecond(r.getExpiresAt().getSeconds())).isAfter(Instant.now());
    }
}
