package com.ecom.order.infrastructure;

import com.ecom.order.domain.CancelReason;
import com.ecom.order.domain.Order;
import com.ecom.order.domain.OrderStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    @EntityGraph(attributePaths = "lines")
    @Query("select o from PurchaseOrder o where o.id = :id")
    Optional<Order> findWithLines(@Param("id") UUID id);

    Page<Order> findByUserId(String userId, Pageable pageable);

    /** Guarded PENDING -> CONFIRMED. Returns 1 if this call won the transition, 0 otherwise. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PurchaseOrder o
               set o.status = com.ecom.order.domain.OrderStatus.CONFIRMED,
                   o.version = o.version + 1,
                   o.updatedAt = :now
             where o.id = :id and o.status = com.ecom.order.domain.OrderStatus.PENDING
            """)
    int confirmIfPending(@Param("id") UUID id, @Param("now") Instant now);

    /** Guarded PENDING -> CANCELLED. Returns 1 if this call won the transition, 0 otherwise. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PurchaseOrder o
               set o.status = com.ecom.order.domain.OrderStatus.CANCELLED,
                   o.cancelReason = :reason,
                   o.version = o.version + 1,
                   o.updatedAt = :now
             where o.id = :id and o.status = com.ecom.order.domain.OrderStatus.PENDING
            """)
    int cancelIfPending(@Param("id") UUID id, @Param("reason") CancelReason reason, @Param("now") Instant now);

    long countByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);
}
