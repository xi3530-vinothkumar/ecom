package com.ecom.payment.infrastructure.persistence;

import com.ecom.payment.domain.PaymentTransaction;
import com.ecom.payment.domain.TransactionType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Optional<PaymentTransaction> findByOrderIdAndType(UUID orderId, TransactionType type);

    /** Scalar lookup: does not load the entity into the persistence context (callers lock first). */
    @Query("select t.orderId from PaymentTransaction t where t.pspReference = :pspReference")
    Optional<UUID> findOrderIdByPspReference(@Param("pspReference") String pspReference);
}
