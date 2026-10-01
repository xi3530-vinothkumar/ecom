package com.ecom.payment.application;

import com.ecom.payment.domain.PaymentTransaction;
import com.ecom.payment.domain.TransactionType;
import com.ecom.payment.infrastructure.persistence.PaymentTransactionRepository;
import com.ecom.platform.core.EcomException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentQueryService {

    private final PaymentTransactionRepository transactions;

    public PaymentQueryService(PaymentTransactionRepository transactions) {
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public PaymentTransaction getByOrderId(String orderId) {
        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException _) {
            throw EcomException.validation("order_id must be a UUID");
        }
        return transactions.findByOrderIdAndType(id, TransactionType.AUTHORIZATION)
                .orElseThrow(() -> EcomException.notFound("No payment transaction for order " + orderId));
    }
}
