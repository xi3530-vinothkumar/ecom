package com.ecom.payment.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.common.events.EventTopics;
import com.ecom.payment.application.PspWebhookService;
import com.ecom.payment.domain.PaymentTransaction;
import com.ecom.payment.domain.TransactionStatus;
import com.ecom.payment.infrastructure.psp.FakePspClient;
import com.ecom.payment.web.FakePspDevController;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PspWebhookIT extends PaymentIntegrationTestBase {

    private PaymentTransaction requiresAction(UUID orderId) {
        sendInventoryReserved(orderId);
        return awaitStatus(orderId, TransactionStatus.REQUIRES_ACTION);
    }

    private byte[] event(String eventId, String type, FakePspClient.Intent intent) {
        return FakePspDevController.stripeEvent(mapper, eventId, type, intent, Instant.now().getEpochSecond());
    }

    private FakePspClient.Intent capturable(PaymentTransaction tx) {
        var intent = psp.intent(tx.pspReference()).orElseThrow();
        return new FakePspClient.Intent(intent.id(), intent.orderId(), intent.amountMinor(), intent.currency(),
                intent.paymentMethodRef(), FakePspClient.STATUS_REQUIRES_CAPTURE, null);
    }

    @Test
    void threeDsSucceededViaDevEndpointAuthorizes() {
        UUID orderId = peers.pendingOrder(THREE_DS, "42.00");
        PaymentTransaction pending = requiresAction(orderId);

        var response = completeAction(pending.pspReference(), "succeeded");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("PROCESSED");
        PaymentTransaction tx = awaitStatus(orderId, TransactionStatus.AUTHORIZED);
        assertThat(tx.clientSecret()).isNull();
        assertThat(awaitPublished(EventTopics.PAYMENT_AUTHORIZED, orderId).path("transactionId").asString())
                .isEqualTo(tx.id().toString());
        assertThat(completeAction(pending.pspReference(), "succeeded").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void threeDsFailedViaDevEndpointPublishesPaymentFailed() {
        UUID orderId = peers.pendingOrder(THREE_DS, "42.00");
        PaymentTransaction pending = requiresAction(orderId);

        completeAction(pending.pspReference(), "failed");

        PaymentTransaction tx = awaitStatus(orderId, TransactionStatus.FAILED);
        assertThat(tx.failureReason()).isEqualTo(FakePspClient.AUTHENTICATION_FAILED);
        assertThat(awaitPublished(EventTopics.PAYMENT_FAILED, orderId).path("reason").asString())
                .isEqualTo(FakePspClient.AUTHENTICATION_FAILED);
        assertThat(outboxCount(EventTopics.PAYMENT_AUTHORIZED, orderId)).isZero();
    }

    @Test
    void lateAuthorizationAfterOrderCancelledIsVoided() {
        UUID orderId = peers.pendingOrder(THREE_DS, "18.00");
        PaymentTransaction pending = requiresAction(orderId);
        var lateEvent = event("evt_late_" + orderId, PspWebhookService.AMOUNT_CAPTURABLE_UPDATED, capturable(pending));

        sendOrderCancelled(orderId);
        PaymentTransaction voided = awaitStatus(orderId, TransactionStatus.VOIDED);
        var response = postWebhook(lateEvent, signer.sign(lateEvent));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(awaitPublished(EventTopics.PAYMENT_VOIDED, orderId).path("transactionId").asString())
                .isEqualTo(voided.id().toString());
        assertThat(transaction(orderId).orElseThrow().status()).isEqualTo(TransactionStatus.VOIDED);
        assertThat(outboxCount(EventTopics.PAYMENT_AUTHORIZED, orderId)).isZero();
        assertThat(outboxCount(EventTopics.PAYMENT_VOIDED, orderId)).isEqualTo(1);
    }

    @Test
    void webhookAuthorizationForCancelledOrderStillAwaitingActionVoidsAtPsp() {
        UUID orderId = peers.pendingOrder(THREE_DS, "18.00");
        PaymentTransaction pending = requiresAction(orderId);
        // Order's cancel decision is on record while the intent is still pending at the PSP
        jdbc.update("INSERT INTO cancelled_orders (order_id, cancelled_at) VALUES (?, now())", orderId);

        var response = completeAction(pending.pspReference(), "succeeded");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PaymentTransaction tx = transaction(orderId).orElseThrow();
        assertThat(tx.status()).isEqualTo(TransactionStatus.VOIDED);
        assertThat(tx.clientSecret()).isNull();
        assertThat(psp.cancelCalls(pending.pspReference())).isEqualTo(1);
        assertThat(psp.intent(pending.pspReference()).orElseThrow().status()).isEqualTo(FakePspClient.STATUS_CANCELED);
        assertThat(awaitPublished(EventTopics.PAYMENT_VOIDED, orderId).path("transactionId").asString())
                .isEqualTo(tx.id().toString());
        assertThat(outboxCount(EventTopics.PAYMENT_AUTHORIZED, orderId)).isZero();
    }

    @Test
    void duplicateWebhookIsProcessedOnce() {
        UUID orderId = peers.pendingOrder(THREE_DS, "11.00");
        PaymentTransaction pending = requiresAction(orderId);
        byte[] body = event("evt_dup_" + orderId, PspWebhookService.AMOUNT_CAPTURABLE_UPDATED, capturable(pending));

        var first = postWebhook(body, signer.sign(body));
        var second = postWebhook(body, signer.sign(body));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).contains("PROCESSED");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).contains("DUPLICATE");
        assertThat(transaction(orderId).orElseThrow().status()).isEqualTo(TransactionStatus.AUTHORIZED);
        assertThat(outboxCount(EventTopics.PAYMENT_AUTHORIZED, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM psp_webhook_events WHERE psp_event_id = ?",
                Integer.class, "evt_dup_" + orderId)).isEqualTo(1);
    }

    @Test
    void webhookForSynchronouslyAuthorizedPaymentIsANoOp() {
        UUID orderId = peers.pendingOrder(VISA, "11.00");
        sendInventoryReserved(orderId);
        PaymentTransaction tx = awaitStatus(orderId, TransactionStatus.AUTHORIZED);
        byte[] body = event("evt_sync_" + orderId, PspWebhookService.AMOUNT_CAPTURABLE_UPDATED,
                psp.intent(tx.pspReference()).orElseThrow());

        assertThat(postWebhook(body, signer.sign(body)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outboxCount(EventTopics.PAYMENT_AUTHORIZED, orderId)).isEqualTo(1);
    }

    @Test
    void badSignaturesAreRejectedWith400BeforeAnyProcessing() {
        UUID orderId = peers.pendingOrder(THREE_DS, "11.00");
        PaymentTransaction pending = requiresAction(orderId);
        byte[] body = event("evt_bad_" + orderId, PspWebhookService.AMOUNT_CAPTURABLE_UPDATED, capturable(pending));
        String valid = signer.sign(body);
        byte[] tampered = new String(body, StandardCharsets.UTF_8).replace("\"amount\":1100", "\"amount\":1")
                .getBytes(StandardCharsets.UTF_8);
        String expired = new com.ecom.payment.infrastructure.psp.StripeSignatureVerifier(
                "whsec_local_dev_secret", Duration.ofMinutes(5),
                Clock.fixed(Instant.now().minus(Duration.ofMinutes(10)), ZoneOffset.UTC)).sign(body);
        String wrongSecret = new com.ecom.payment.infrastructure.psp.StripeSignatureVerifier(
                "whsec_attacker", Duration.ofMinutes(5), Clock.systemUTC()).sign(body);

        assertThat(postWebhook(body, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postWebhook(tampered, valid).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postWebhook(body, expired).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postWebhook(body, wrongSecret).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postWebhook(body, "t=1,v1=00").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(transaction(orderId).orElseThrow().status()).isEqualTo(TransactionStatus.REQUIRES_ACTION);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM psp_webhook_events WHERE psp_event_id = ?",
                Integer.class, "evt_bad_" + orderId)).isZero();
    }

    @Test
    void unknownEventTypesAreAcknowledgedAndIgnored() {
        byte[] body = "{\"id\":\"evt_unknown_type\",\"type\":\"charge.refunded\",\"data\":{\"object\":{}}}"
                .getBytes(StandardCharsets.UTF_8);
        var response = postWebhook(body, signer.sign(body));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("IGNORED");
    }

    @Test
    void signedButMalformedBodyIs400() {
        byte[] body = "not json".getBytes(StandardCharsets.UTF_8);
        assertThat(postWebhook(body, signer.sign(body)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void webhookBeforeOurRecordCommitsAsksPspToRetry() {
        String orderId = UUID.randomUUID().toString();
        var intent = new FakePspClient.Intent("pi_not_yet_" + orderId, orderId, 100, "eur", VISA,
                FakePspClient.STATUS_REQUIRES_CAPTURE, null);
        byte[] body = event("evt_early_" + orderId, PspWebhookService.AMOUNT_CAPTURABLE_UPDATED, intent);

        assertThat(postWebhook(body, signer.sign(body)).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM psp_webhook_events WHERE psp_event_id = ?",
                Integer.class, "evt_early_" + orderId)).isZero();
    }
}
