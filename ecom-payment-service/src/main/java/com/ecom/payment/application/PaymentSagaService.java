package com.ecom.payment.application;

import com.ecom.common.events.Money;
import com.ecom.inventory.proto.v1.ReservationStatus;
import com.ecom.order.proto.v1.Order;
import com.ecom.order.proto.v1.OrderStatus;
import com.ecom.payment.application.psp.AuthorizationResult;
import com.ecom.payment.application.psp.PspClient;
import com.ecom.payment.domain.PaymentTransaction;
import com.ecom.payment.domain.TransactionStatus;
import com.ecom.payment.domain.TransactionType;
import com.ecom.payment.infrastructure.PeerClients;
import com.ecom.payment.infrastructure.persistence.PaymentJdbcStore;
import com.ecom.payment.infrastructure.persistence.PaymentTransactionRepository;
import com.ecom.platform.core.MoneyMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment's side of the order saga (PLAN §3). Every method runs inside the idempotent consumer's
 * transaction (MANDATORY) and starts by taking the per-order lock, so the three consumers and the
 * webhook never interleave for the same order.
 *
 * <p><b>Why calling the PSP inside the consumer transaction is safe.</b> The PSP call cannot be
 * rolled back, but it does not need to be: the idempotency key is the order id, so if the local
 * commit fails after the PSP answered (crash, lost connection, constraint violation), the redelivered
 * event repeats the call and the PSP replays the <i>same</i> intent and outcome instead of creating a
 * second authorization. The unique {@code (order_id, type)} constraint guarantees at most one local
 * row even if two deliveries race past the "existing transaction" check. Cancel and capture carry
 * their own idempotency keys and are no-ops at the PSP when repeated.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PaymentSagaService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSagaService.class);

    private final PaymentTransactionRepository transactions;
    private final PaymentJdbcStore store;
    private final PeerClients peers;
    private final PspClient psp;
    private final PaymentEvents events;
    private final Clock clock;

    public PaymentSagaService(PaymentTransactionRepository transactions, PaymentJdbcStore store, PeerClients peers,
            PspClient psp, PaymentEvents events, Clock clock) {
        this.transactions = transactions;
        this.store = store;
        this.peers = peers;
        this.psp = psp;
        this.events = events;
        this.clock = clock;
    }

    public void onInventoryReserved(UUID orderId) {
        store.lockOrder(orderId);
        if (store.isOrderCancelled(orderId)) {
            log.info("Order {} already cancelled; not authorizing", orderId);
            return;
        }
        if (transactions.findByOrderIdAndType(orderId, TransactionType.AUTHORIZATION).isPresent()) {
            log.info("Order {} already has a payment transaction; not calling the PSP again", orderId);
            return;
        }
        Optional<Order> maybeOrder = peers.getOrder(orderId);
        if (maybeOrder.isEmpty() || maybeOrder.get().getStatus() != OrderStatus.PENDING) {
            log.info("Order {} is {}; not authorizing", orderId,
                    maybeOrder.map(o -> o.getStatus().name()).orElse("unknown to Order service"));
            return;
        }
        Order order = maybeOrder.get();
        var reservation = peers.getReservation(orderId);
        if (reservation.isEmpty() || reservation.get().getStatus() != ReservationStatus.RESERVED) {
            log.info("Reservation for order {} is {}; not authorizing", orderId,
                    reservation.map(r -> r.getStatus().name()).orElse("missing"));
            return;
        }

        Money amount = MoneyMapper.fromProto(order.getTotal());
        AuthorizationResult result = psp.authorize(
                orderId.toString(), amount.toMinorUnits(), amount.currencyCode(), order.getPaymentMethodRef());
        Instant now = clock.instant();
        PaymentTransaction tx = switch (result) {
            case AuthorizationResult.Authorized a -> PaymentTransaction.authorized(orderId, amount, a.pspReference(), now);
            case AuthorizationResult.Declined d ->
                    PaymentTransaction.failed(orderId, amount, d.pspReference(), d.reason(), now);
            case AuthorizationResult.RequiresAction r ->
                    PaymentTransaction.requiresAction(orderId, amount, r.pspReference(), r.clientSecret(), now);
        };
        transactions.saveAndFlush(tx);
        store.recordPaymentMethod(UUID.fromString(order.getUserId()), order.getPaymentMethodRef(),
                result.card().brand(), result.card().last4(), now);

        switch (tx.status()) {
            case AUTHORIZED -> events.authorized(tx);
            case FAILED -> events.failed(tx);
            // customer must complete 3DS; the PSP webhook decides (PspWebhookService)
            case REQUIRES_ACTION -> log.info("Order {} payment requires customer action ({})", orderId, tx.pspReference());
            case VOIDED, CAPTURED -> log.warn("Order {} new authorization unexpectedly {}", orderId, tx.status());
        }
    }

    public void onOrderCancelled(UUID orderId) {
        store.lockOrder(orderId);
        store.markOrderCancelled(orderId, clock.instant());
        transactions.findByOrderIdAndType(orderId, TransactionType.AUTHORIZATION)
                .ifPresentOrElse(tx -> {
                    if (tx.status().isVoidable()) {
                        voidAtPsp(tx);
                    } else if (tx.status() == TransactionStatus.CAPTURED) {
                        log.error("Order {} cancelled but payment {} is already CAPTURED; needs a refund", orderId, tx.id());
                    }
                }, () -> log.info("Order {} cancelled before any payment was attempted", orderId));
    }

    public void onOrderConfirmed(UUID orderId) {
        store.lockOrder(orderId);
        Optional<PaymentTransaction> maybeTx = transactions.findByOrderIdAndType(orderId, TransactionType.AUTHORIZATION);
        if (maybeTx.isEmpty()) {
            log.error("Order {} confirmed without a payment transaction", orderId);
            return;
        }
        PaymentTransaction tx = maybeTx.get();
        switch (tx.status()) {
            case CAPTURED -> log.debug("Order {} already captured", orderId);
            case AUTHORIZED -> {
                psp.capture(tx.pspReference());
                tx.markCaptured(clock.instant());
                log.info("Captured payment {} for order {}", tx.id(), orderId);
            }
            case REQUIRES_ACTION, FAILED, VOIDED ->
                    log.error("Order {} confirmed but payment {} is {}; not capturing", orderId, tx.id(), tx.status());
        }
    }

    /** Releases the authorization at the PSP and records VOIDED + payment-voided. */
    public void voidAtPsp(PaymentTransaction tx) {
        psp.cancel(tx.pspReference());
        tx.markVoided(clock.instant());
        events.voided(tx);
        log.info("Voided payment {} for cancelled order {}", tx.id(), tx.orderId());
    }
}
