package com.ecom.payment.grpc;

import com.ecom.payment.application.PaymentQueryService;
import com.ecom.payment.domain.PaymentTransaction;
import com.ecom.payment.domain.TransactionStatus;
import com.ecom.payment.proto.v1.GetTransactionRequest;
import com.ecom.payment.proto.v1.PaymentServiceGrpc;
import com.ecom.platform.core.MoneyMapper;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

@Component
public class PaymentGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase {

    private final PaymentQueryService queries;

    public PaymentGrpcService(PaymentQueryService queries) {
        this.queries = queries;
    }

    @Override
    public void getTransaction(GetTransactionRequest request,
            StreamObserver<com.ecom.payment.proto.v1.PaymentTransaction> response) {
        response.onNext(toProto(queries.getByOrderId(request.getOrderId())));
        response.onCompleted();
    }

    static com.ecom.payment.proto.v1.PaymentTransaction toProto(PaymentTransaction tx) {
        var builder = com.ecom.payment.proto.v1.PaymentTransaction.newBuilder()
                .setTransactionId(tx.id().toString())
                .setOrderId(tx.orderId().toString())
                .setStatus(tx.status().name())
                .setAmount(MoneyMapper.toProto(tx.amountMoney()));
        if (tx.pspReference() != null) {
            builder.setPspReference(tx.pspReference());
        }
        if (tx.status() == TransactionStatus.REQUIRES_ACTION && tx.clientSecret() != null) {
            builder.setClientSecret(tx.clientSecret());
        }
        return builder.build();
    }
}
