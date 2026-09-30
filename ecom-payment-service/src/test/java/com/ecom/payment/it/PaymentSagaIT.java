package com.ecom.payment.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.common.events.EventTopics;
import com.ecom.inventory.proto.v1.ReservationStatus;
import com.ecom.order.proto.v1.OrderStatus;
import com.ecom.payment.domain.PaymentTransaction;
import com.ecom.payment.domain.TransactionStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PaymentSagaIT extends PaymentIntegrationTestBase {

    private static final String SAGA_GROUP = "payment-service-saga-cg";
    private static final String ORDER_GROUP = "payment-service-order-events-cg";

    @Test
    void authorizesAndPublishesPaymentAuthorized() {
        UUID orderId = peers.pendingOrder(VISA, "25.90");

        sendInventoryReserved(orderId);

        PaymentTransaction tx = awaitStatus(orderId, TransactionStatus.AUTHORIZED);
        assertThat(tx.pspReference()).startsWith("pi_fake_");
        assertThat(tx.clientSecret()).isNull();
        assertThat(tx.amountMoney().toBigDecimal()).isEqualByComparingTo("25.90");
        JsonNode event = awaitPublished(EventTopics.PAYMENT_AUTHORIZED, orderId);
        assertThat(event.path("transactionId").asString()).isEqualTo(tx.id().toString());
        assertThat(event.path("amount").path("units").asLong()).isEqualTo(25);
        assertThat(event.path("amount").path("nanos").asInt()).isEqualTo(900_000_000);
        assertThat(psp.intent(tx.pspReference()).orElseThrow().amountMinor()).isEqualTo(2590);
        assertThat(jdbc.queryForObject(
                "SELECT brand || ':' || last4 FROM payment_methods WHERE user_id = ?::uuid AND psp_ref = ?",
                String.class, peers.userIdOf(orderId), VISA)).isEqualTo("visa:4242");
    }

    @Test
    void declinePublishesPaymentFailed() {
        UUID orderId = peers.pendingOrder(DECLINED, "12.00");

        sendInventoryReserved(orderId);

        PaymentTransaction tx = awaitStatus(orderId, TransactionStatus.FAILED);
        assertThat(tx.failureReason()).isEqualTo("card_declined");
        JsonNode event = awaitPublished(EventTopics.PAYMENT_FAILED, orderId);
        assertThat(event.path("reason").asString()).isEqualTo("card_declined");
        assertThat(event.path("transactionId").asString()).isEqualTo(tx.id().toString());
        assertThat(outboxCount(EventTopics.PAYMENT_AUTHORIZED, orderId)).isZero();
    }

    @Test
    void threeDsIsStoredAsRequiresActionWithoutEvent() {
        UUID orderId = peers.pendingOrder(THREE_DS, "40.00");

        var env = sendInventoryReserved(orderId);

        PaymentTransaction tx = awaitStatus(orderId, TransactionStatus.REQUIRES_ACTION);
        awaitProcessed(env, SAGA_GROUP);
        assertThat(tx.clientSecret()).startsWith(tx.pspReference() + "_secret_");
        assertThat(outboxCount(EventTopics.PAYMENT_AUTHORIZED, orderId)).isZero();
        assertThat(outboxCount(EventTopics.PAYMENT_FAILED, orderId)).isZero();
    }

    @Test
    void duplicateInventoryReservedCallsPspExactlyOnce() {
        UUID orderId = peers.pendingOrder(VISA, "9.99");

        var first = sendInventoryReserved(orderId);
        resend(EventTopics.INVENTORY_RESERVED, orderId, first); // same eventId: processed_events dedup
        var second = sendInventoryReserved(orderId);           // new eventId: existing-transaction guard

        awaitProcessed(first, SAGA_GROUP);
        awaitProcessed(second, SAGA_GROUP);
        awaitStatus(orderId, TransactionStatus.AUTHORIZED);
        assertThat(psp.authorizeCalls(orderId.toString())).isEqualTo(1);
        assertThat(peers.getOrderCalls(orderId)).isEqualTo(1);
        assertThat(outboxCount(EventTopics.PAYMENT_AUTHORIZED, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE order_id = ?",
                Integer.class, orderId)).isEqualTo(1);
    }

    @Test
    void skipsWhenOrderNotPendingOrReservationNotReserved() {
        UUID confirmed = peers.pendingOrder(VISA, "5.00");
        peers.putOrder(confirmed, OrderStatus.CANCELLED, VISA, "5.00");
        UUID released = peers.pendingOrder(VISA, "5.00");
        peers.putReservation(released, ReservationStatus.RELEASED);
        UUID unknown = UUID.randomUUID();

        var e1 = sendInventoryReserved(confirmed);
        var e2 = sendInventoryReserved(released);
        var e3 = sendInventoryReserved(unknown);

        awaitProcessed(e1, SAGA_GROUP);
        awaitProcessed(e2, SAGA_GROUP);
        awaitProcessed(e3, SAGA_GROUP);
        for (UUID orderId : new UUID[] {confirmed, released, unknown}) {
            assertThat(transaction(orderId)).isEmpty();
            assertThat(psp.authorizeCalls(orderId.toString())).isZero();
        }
    }

    @Test
    void cancelBeforeReservedPreventsAuthorization() {
        UUID orderId = peers.pendingOrder(VISA, "5.00");

        var cancel = sendOrderCancelled(orderId);
        awaitProcessed(cancel, ORDER_GROUP);
        var reserved = sendInventoryReserved(orderId);
        awaitProcessed(reserved, SAGA_GROUP);

        assertThat(transaction(orderId)).isEmpty();
        assertThat(psp.authorizeCalls(orderId.toString())).isZero();
    }

    @Test
    void orderCancelledVoidsAuthorization() {
        UUID orderId = peers.pendingOrder(VISA, "30.00");
        sendInventoryReserved(orderId);
        PaymentTransaction authorized = awaitStatus(orderId, TransactionStatus.AUTHORIZED);

        var cancel = sendOrderCancelled(orderId);
        resend(EventTopics.ORDER_CANCELLED, orderId, cancel);

        PaymentTransaction voided = awaitStatus(orderId, TransactionStatus.VOIDED);
        assertThat(awaitPublished(EventTopics.PAYMENT_VOIDED, orderId).path("transactionId").asString())
                .isEqualTo(voided.id().toString());
        assertThat(psp.intent(authorized.pspReference()).orElseThrow().status()).isEqualTo(FakePspClientStatus.CANCELED);
        assertThat(psp.cancelCalls(authorized.pspReference())).isEqualTo(1);
        assertThat(outboxCount(EventTopics.PAYMENT_VOIDED, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cancelled_orders WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
    }

    @Test
    void orderConfirmedCapturesOnce() {
        UUID orderId = peers.pendingOrder(VISA, "15.00");
        sendInventoryReserved(orderId);
        PaymentTransaction authorized = awaitStatus(orderId, TransactionStatus.AUTHORIZED);

        sendOrderConfirmed(orderId);
        awaitStatus(orderId, TransactionStatus.CAPTURED);
        var again = sendOrderConfirmed(orderId);
        awaitProcessed(again, ORDER_GROUP);

        assertThat(transaction(orderId).orElseThrow().status()).isEqualTo(TransactionStatus.CAPTURED);
        assertThat(psp.captureCalls(authorized.pspReference())).isEqualTo(1);
        assertThat(psp.intent(authorized.pspReference()).orElseThrow().status()).isEqualTo(FakePspClientStatus.SUCCEEDED);
    }

    /** Short aliases for the fake PSP's Stripe status strings. */
    private static final class FakePspClientStatus {
        static final String CANCELED = com.ecom.payment.infrastructure.psp.FakePspClient.STATUS_CANCELED;
        static final String SUCCEEDED = com.ecom.payment.infrastructure.psp.FakePspClient.STATUS_SUCCEEDED;
    }
}
