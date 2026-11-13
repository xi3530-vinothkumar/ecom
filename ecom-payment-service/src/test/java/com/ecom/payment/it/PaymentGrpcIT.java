package com.ecom.payment.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecom.payment.domain.PaymentTransaction;
import com.ecom.payment.domain.TransactionStatus;
import com.ecom.payment.proto.v1.GetTransactionRequest;
import com.ecom.payment.proto.v1.PaymentServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

class PaymentGrpcIT extends PaymentIntegrationTestBase {

    @Autowired GrpcServerLifecycle grpcServer;

    private ManagedChannel channel;
    private PaymentServiceGrpc.PaymentServiceBlockingStub stub;

    @BeforeEach
    void connect() {
        channel = NettyChannelBuilder.forAddress("localhost", grpcServer.getPort()).usePlaintext().build();
        stub = PaymentServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void disconnect() {
        channel.shutdownNow();
    }

    private GetTransactionRequest request(UUID orderId) {
        return GetTransactionRequest.newBuilder().setOrderId(orderId.toString()).build();
    }

    @Test
    void exposesClientSecretOnlyWhileRequiresAction() {
        UUID orderId = peers.pendingOrder(THREE_DS, "20.00");
        sendInventoryReserved(orderId);
        PaymentTransaction pending = awaitStatus(orderId, TransactionStatus.REQUIRES_ACTION);

        var proto = stub.getTransaction(request(orderId));
        assertThat(proto.getStatus()).isEqualTo("REQUIRES_ACTION");
        assertThat(proto.getClientSecret()).isEqualTo(pending.clientSecret());
        assertThat(proto.getPspReference()).isEqualTo(pending.pspReference());
        assertThat(proto.getTransactionId()).isEqualTo(pending.id().toString());
        assertThat(proto.getAmount().getUnits()).isEqualTo(20);
        assertThat(proto.getAmount().getCurrencyCode()).isEqualTo("EUR");

        completeAction(pending.pspReference(), "succeeded");
        awaitStatus(orderId, TransactionStatus.AUTHORIZED);
        var authorized = stub.getTransaction(request(orderId));
        assertThat(authorized.getStatus()).isEqualTo("AUTHORIZED");
        assertThat(authorized.getClientSecret()).isEmpty();
    }

    @Test
    void unknownOrderIsNotFound() {
        assertThatThrownBy(() -> stub.getTransaction(request(UUID.randomUUID())))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
        assertThatThrownBy(() -> stub.getTransaction(GetTransactionRequest.newBuilder().setOrderId("nope").build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }
}
