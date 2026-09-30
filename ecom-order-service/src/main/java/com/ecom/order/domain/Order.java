package com.ecom.order.domain;

import com.ecom.common.events.Money;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Order aggregate. Status changes never go through entity setters: they are guarded bulk updates in
 * {@code OrderRepository} so concurrent saga events cannot both win.
 */
@Entity(name = "PurchaseOrder")
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason")
    private CancelReason cancelReason;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, columnDefinition = "bpchar(64)")
    private String requestHash;

    @Column(name = "currency", nullable = false, columnDefinition = "bpchar(3)")
    private String currency;

    @Column(name = "subtotal_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotalAmount;

    @Column(name = "vat_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal vatAmount;

    @Column(name = "shipping_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal shippingAmount;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "payment_method_ref", nullable = false)
    private String paymentMethodRef;

    @Embedded
    private ShippingAddress shippingAddress;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "order_lines", joinColumns = @JoinColumn(name = "order_id"))
    @OrderBy("sku")
    private List<OrderLine> lines = new ArrayList<>();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Order() {}

    public static Order placePending(
            String userId,
            String idempotencyKey,
            String requestHash,
            String paymentMethodRef,
            ShippingAddress shippingAddress,
            List<PricedLine> pricedLines,
            OrderTotals totals,
            Instant now) {
        Order o = new Order();
        o.id = UUID.randomUUID();
        o.userId = userId;
        o.status = OrderStatus.PENDING;
        o.idempotencyKey = idempotencyKey;
        o.requestHash = requestHash;
        o.currency = totals.total().currencyCode();
        o.subtotalAmount = MoneyColumns.toColumn(totals.subtotal());
        o.vatAmount = MoneyColumns.toColumn(totals.vat());
        o.shippingAmount = MoneyColumns.toColumn(totals.shipping());
        o.totalAmount = MoneyColumns.toColumn(totals.total());
        o.paymentMethodRef = paymentMethodRef;
        o.shippingAddress = shippingAddress;
        pricedLines.forEach(l -> o.lines.add(OrderLine.from(l)));
        o.createdAt = now;
        o.updatedAt = now;
        return o;
    }

    public UUID id() {
        return id;
    }

    public String userId() {
        return userId;
    }

    public OrderStatus status() {
        return status;
    }

    public CancelReason cancelReason() {
        return cancelReason;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String requestHash() {
        return requestHash;
    }

    public String currency() {
        return currency;
    }

    public Money subtotal() {
        return MoneyColumns.fromColumn(subtotalAmount, currency);
    }

    public Money vat() {
        return MoneyColumns.fromColumn(vatAmount, currency);
    }

    public Money shipping() {
        return MoneyColumns.fromColumn(shippingAmount, currency);
    }

    public Money total() {
        return MoneyColumns.fromColumn(totalAmount, currency);
    }

    public String paymentMethodRef() {
        return paymentMethodRef;
    }

    public ShippingAddress shippingAddress() {
        return shippingAddress;
    }

    public List<OrderLine> lines() {
        return Collections.unmodifiableList(lines);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
