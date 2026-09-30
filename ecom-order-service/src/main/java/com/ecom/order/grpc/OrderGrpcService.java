package com.ecom.order.grpc;

import com.ecom.order.application.OrderQueryService;
import com.ecom.order.proto.v1.GetOrderRequest;
import com.ecom.order.proto.v1.Order;
import com.ecom.order.proto.v1.OrderLine;
import com.ecom.order.proto.v1.OrderServiceGrpc;
import com.ecom.order.proto.v1.OrderStatus;
import com.ecom.platform.core.MoneyMapper;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

/** Internal: Payment reads amount, currency, payment method and status from here. */
@Component
public class OrderGrpcService extends OrderServiceGrpc.OrderServiceImplBase {

    private final OrderQueryService queries;

    public OrderGrpcService(OrderQueryService queries) {
        this.queries = queries;
    }

    @Override
    public void getOrder(GetOrderRequest request, StreamObserver<Order> response) {
        com.ecom.order.domain.Order o = queries.getInternal(request.getOrderId());
        Order.Builder b = Order.newBuilder()
                .setOrderId(o.id().toString())
                .setUserId(o.userId())
                .setStatus(toProto(o.status()))
                .setTotal(MoneyMapper.toProto(o.total()))
                .setPaymentMethodRef(o.paymentMethodRef());
        o.lines().forEach(l -> b.addLines(OrderLine.newBuilder()
                .setSku(l.sku())
                .setName(l.name())
                .setQuantity(l.quantity())
                .setUnitPrice(MoneyMapper.toProto(l.unitPrice(o.currency())))));
        response.onNext(b.build());
        response.onCompleted();
    }

    static OrderStatus toProto(com.ecom.order.domain.OrderStatus status) {
        return switch (status) {
            case PENDING -> OrderStatus.PENDING;
            case CONFIRMED -> OrderStatus.CONFIRMED;
            case CANCELLED -> OrderStatus.CANCELLED;
        };
    }
}
