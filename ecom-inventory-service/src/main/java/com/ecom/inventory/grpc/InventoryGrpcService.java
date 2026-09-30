package com.ecom.inventory.grpc;

import com.ecom.inventory.application.InventoryQueries;
import com.ecom.inventory.domain.SkuQuantity;
import com.ecom.inventory.proto.v1.CheckAvailabilityRequest;
import com.ecom.inventory.proto.v1.CheckAvailabilityResponse;
import com.ecom.inventory.proto.v1.GetReservationRequest;
import com.ecom.inventory.proto.v1.InventoryServiceGrpc;
import com.ecom.inventory.proto.v1.Reservation;
import com.ecom.inventory.proto.v1.ReservationStatus;
import com.ecom.inventory.proto.v1.SkuAvailability;
import com.ecom.platform.core.EcomException;
import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class InventoryGrpcService extends InventoryServiceGrpc.InventoryServiceImplBase {

    private final InventoryQueries queries;

    public InventoryGrpcService(InventoryQueries queries) {
        this.queries = queries;
    }

    @Override
    public void checkAvailability(CheckAvailabilityRequest request, StreamObserver<CheckAvailabilityResponse> out) {
        List<SkuQuantity> items = validated(() -> request.getItemsList().stream()
                .map(i -> new SkuQuantity(i.getSku(), i.getQuantity()))
                .toList());
        List<InventoryQueries.Availability> result = validated(() -> queries.checkAvailability(items));
        var response = CheckAvailabilityResponse.newBuilder();
        result.forEach(a -> response.addItems(SkuAvailability.newBuilder()
                .setSku(a.sku())
                .setAvailable(a.available())
                .setSufficient(a.sufficient())));
        response.setAllAvailable(result.stream().allMatch(InventoryQueries.Availability::sufficient));
        out.onNext(response.build());
        out.onCompleted();
    }

    @Override
    public void getReservation(GetReservationRequest request, StreamObserver<Reservation> out) {
        UUID orderId = validated(() -> UUID.fromString(request.getOrderId()));
        InventoryQueries.OrderReservation r = queries.getReservation(orderId);
        var reservation = Reservation.newBuilder()
                .setOrderId(r.orderId().toString())
                .setStatus(ReservationStatus.valueOf(r.status().name()))
                .setExpiresAt(Timestamp.newBuilder()
                        .setSeconds(r.expiresAt().getEpochSecond())
                        .setNanos(r.expiresAt().getNano()));
        r.lines().forEach(l -> reservation.addLines(com.ecom.inventory.proto.v1.SkuQuantity.newBuilder()
                .setSku(l.sku())
                .setQuantity(l.quantity())));
        out.onNext(reservation.build());
        out.onCompleted();
    }

    private static <T> T validated(java.util.function.Supplier<T> body) {
        try {
            return body.get();
        } catch (IllegalArgumentException e) {
            throw EcomException.validation(e.getMessage());
        }
    }
}
