package com.ecom.order.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.common.events.Money;
import com.ecom.order.application.StalePendingOrdersGauge;
import com.ecom.order.proto.v1.GetOrderRequest;
import com.ecom.order.proto.v1.Order;
import com.ecom.order.proto.v1.OrderServiceGrpc;
import com.ecom.order.proto.v1.OrderStatus;
import com.ecom.payment.proto.v1.PaymentTransaction;
import com.ecom.platform.core.MoneyMapper;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class OrderQueryIT extends AbstractOrderIT {

    @Autowired GrpcServerLifecycle grpcServer;
    @Autowired StalePendingOrdersGauge staleGauge;
    @Autowired MeterRegistry meters;

    @Test
    void ownerSeesOrderWithLinesAndTotals() {
        String user = userWithCart();
        UUID id = placeOrder(user);

        ResponseEntity<Map> res = get(user, "/orders/" + id);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).containsEntry("status", "PENDING").doesNotContainKey("paymentAction");
        assertThat(body.get("total")).isEqualTo(Map.of("amount", "35.81", "currency", "EUR"));
        assertThat(body.get("vat")).isEqualTo(Map.of("amount", "5.36", "currency", "EUR"));
        List<Map<String, Object>> lines = (List<Map<String, Object>>) body.get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).containsEntry("sku", "SKU-A").containsEntry("quantity", 2);
        assertThat(lines.getFirst().get("lineTotal")).isEqualTo(Map.of("amount", "20.00", "currency", "EUR"));
        assertThat((Map<String, Object>) body.get("shippingAddress")).containsEntry("city", "Amsterdam");
    }

    @Test
    void otherUsersAndUnknownIdsGet404() {
        String owner = userWithCart();
        UUID id = placeOrder(owner);

        assertThat(get(newUser(), "/orders/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(owner, "/orders/" + UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(owner, "/orders/not-a-uuid").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(null, "/orders/" + id).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void pendingOrderExposes3dsActionWhenPaymentRequiresIt() {
        String user = userWithCart();
        UUID id = placeOrder(user);
        FakePeers.transactions.put(id.toString(), PaymentTransaction.newBuilder()
                .setOrderId(id.toString())
                .setStatus("REQUIRES_ACTION")
                .setClientSecret("pi_123_secret_abc")
                .setAmount(MoneyMapper.toProto(Money.of("35.81", "EUR")))
                .build());

        Map<String, Object> body = get(user, "/orders/" + id).getBody();

        assertThat(body.get("paymentAction")).isEqualTo(Map.of("type", "3ds", "clientSecret", "pi_123_secret_abc"));
    }

    @Test
    void authorizedTransactionMeansNoPaymentAction() {
        String user = userWithCart();
        UUID id = placeOrder(user);
        FakePeers.transactions.put(id.toString(), PaymentTransaction.newBuilder()
                .setOrderId(id.toString()).setStatus("AUTHORIZED").build());

        assertThat(get(user, "/orders/" + id).getBody()).doesNotContainKey("paymentAction");
    }

    @Test
    void listReturnsOnlyMyOrdersNewestFirstPaged() {
        String user = userWithCart();
        UUID first = placeOrder(user);
        UUID second = placeOrder(user);
        UUID third = placeOrder(user);
        placeOrder(userWithCart());

        Map<String, Object> page0 = get(user, "/orders?page=0&size=2").getBody();
        Map<String, Object> page1 = get(user, "/orders?page=1&size=2").getBody();

        assertThat(page0).containsEntry("totalElements", 3).containsEntry("totalPages", 2);
        assertThat((List<Map<String, Object>>) page0.get("items")).extracting(m -> m.get("orderId"))
                .containsExactly(third.toString(), second.toString());
        assertThat((List<Map<String, Object>>) page1.get("items")).extracting(m -> m.get("orderId"))
                .containsExactly(first.toString());
        assertThat(get(user, "/orders?size=1000").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void grpcGetOrderReturnsTotalLinesAndPaymentMethod() {
        String user = userWithCart();
        UUID id = placeOrder(user);
        ManagedChannel ch = NettyChannelBuilder.forAddress("localhost", grpcServer.getPort()).usePlaintext().build();
        try {
            var stub = OrderServiceGrpc.newBlockingStub(ch);
            Order o = stub.getOrder(GetOrderRequest.newBuilder().setOrderId(id.toString()).build());

            assertThat(o.getUserId()).isEqualTo(user);
            assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(MoneyMapper.fromProto(o.getTotal()).toBigDecimal()).isEqualByComparingTo("35.81");
            assertThat(o.getPaymentMethodRef()).isEqualTo("pm_card_visa");
            assertThat(o.getLinesList()).extracting(l -> l.getSku() + "x" + l.getQuantity()).containsExactly("SKU-Ax2", "SKU-Bx1");

            StatusRuntimeException notFound = org.junit.jupiter.api.Assertions.assertThrows(StatusRuntimeException.class,
                    () -> stub.getOrder(GetOrderRequest.newBuilder().setOrderId(UUID.randomUUID().toString()).build()));
            assertThat(notFound.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
            StatusRuntimeException malformed = org.junit.jupiter.api.Assertions.assertThrows(StatusRuntimeException.class,
                    () -> stub.getOrder(GetOrderRequest.newBuilder().setOrderId("x").build()));
            assertThat(malformed.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        } finally {
            ch.shutdownNow();
        }
    }

    @Test
    void staleGaugeCountsOldPendingOrders() {
        staleGauge.refresh();
        long before = staleGauge.current();
        UUID stale = placeOrder(userWithCart());
        placeOrder(userWithCart());
        jdbc.update("UPDATE orders SET created_at = now() - interval '31 minutes' WHERE id = ?", stale);

        staleGauge.refresh();

        assertThat(staleGauge.current()).isEqualTo(before + 1);
        assertThat(meters.get("ecom.order.pending.stale").gauge().value()).isEqualTo(before + 1);
        jdbc.update("UPDATE orders SET status = 'CANCELLED', cancel_reason = 'PAYMENT_TIMEOUT' WHERE id = ?", stale);
    }
}
