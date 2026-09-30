package com.ecom.payment.domain;

import com.ecom.common.events.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Authorization lifecycle of one order's payment:
 * <pre>
 * REQUIRES_ACTION -> AUTHORIZED | FAILED | VOIDED
 * AUTHORIZED      -> CAPTURED | VOIDED
 * </pre>
 * The client secret only exists while the customer still has to complete 3DS/SCA.
 */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 32)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TransactionStatus status;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "psp_reference")
    private String pspReference;

    @Column(name = "client_secret")
    private String clientSecret;

    @Column(name = "failure_reason")
    private String failureReason;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentTransaction() {}

    private PaymentTransaction(UUID orderId, Money amount, TransactionStatus status, String pspReference,
            String clientSecret, String failureReason, Instant now) {
        this.id = UUID.randomUUID();
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.type = TransactionType.AUTHORIZATION;
        this.status = status;
        this.amount = amount.toBigDecimal();
        this.currency = amount.currencyCode();
        this.pspReference = pspReference;
        this.clientSecret = clientSecret;
        this.failureReason = failureReason;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static PaymentTransaction authorized(UUID orderId, Money amount, String pspReference, Instant now) {
        return new PaymentTransaction(orderId, amount, TransactionStatus.AUTHORIZED,
                Objects.requireNonNull(pspReference, "pspReference"), null, null, now);
    }

    public static PaymentTransaction failed(UUID orderId, Money amount, String pspReference, String reason, Instant now) {
        return new PaymentTransaction(orderId, amount, TransactionStatus.FAILED, pspReference, null,
                Objects.requireNonNull(reason, "reason"), now);
    }

    public static PaymentTransaction requiresAction(
            UUID orderId, Money amount, String pspReference, String clientSecret, Instant now) {
        return new PaymentTransaction(orderId, amount, TransactionStatus.REQUIRES_ACTION,
                Objects.requireNonNull(pspReference, "pspReference"),
                Objects.requireNonNull(clientSecret, "clientSecret"), null, now);
    }

    public void markAuthorized(Instant now) {
        transition(EnumSet.of(TransactionStatus.REQUIRES_ACTION), TransactionStatus.AUTHORIZED, now);
    }

    public void markFailed(String reason, Instant now) {
        transition(EnumSet.of(TransactionStatus.REQUIRES_ACTION), TransactionStatus.FAILED, now);
        this.failureReason = Objects.requireNonNull(reason, "reason");
    }

    public void markVoided(Instant now) {
        transition(EnumSet.of(TransactionStatus.REQUIRES_ACTION, TransactionStatus.AUTHORIZED), TransactionStatus.VOIDED, now);
    }

    public void markCaptured(Instant now) {
        transition(EnumSet.of(TransactionStatus.AUTHORIZED), TransactionStatus.CAPTURED, now);
    }

    private void transition(Set<TransactionStatus> allowedFrom, TransactionStatus to, Instant now) {
        if (!allowedFrom.contains(status)) {
            throw new IllegalTransitionException(status, to);
        }
        this.status = to;
        this.clientSecret = null;
        this.updatedAt = now;
    }

    public Money amountMoney() {
        return Money.of(amount, currency);
    }

    public UUID id() {
        return id;
    }

    public UUID orderId() {
        return orderId;
    }

    public TransactionType type() {
        return type;
    }

    public TransactionStatus status() {
        return status;
    }

    public String pspReference() {
        return pspReference;
    }

    /** Present only while {@link TransactionStatus#REQUIRES_ACTION}. */
    public String clientSecret() {
        return clientSecret;
    }

    public String failureReason() {
        return failureReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
