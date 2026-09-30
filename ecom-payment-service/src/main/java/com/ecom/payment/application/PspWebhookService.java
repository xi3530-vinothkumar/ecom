package com.ecom.payment.application;

import com.ecom.payment.domain.PaymentTransaction;
import com.ecom.payment.domain.TransactionStatus;
import com.ecom.payment.domain.TransactionType;
import com.ecom.payment.infrastructure.persistence.PaymentJdbcStore;
import com.ecom.payment.infrastructure.persistence.PaymentTransactionRepository;
import com.ecom.payment.infrastructure.psp.StripeSignatureVerifier;
import com.ecom.payment.infrastructure.psp.StripeSignatureVerifier.InvalidSignatureException;
import com.ecom.platform.core.EcomException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Stripe-shaped PSP webhooks. The signature is verified over the raw bytes before anything is
 * parsed. Processing (dedup row + state change + outbox) is one transaction under the per-order lock.
 */
@Service
public class PspWebhookService {

    public static final String AMOUNT_CAPTURABLE_UPDATED = "payment_intent.amount_capturable_updated";
    public static final String PAYMENT_FAILED = "payment_intent.payment_failed";
    private static final Set<String> HANDLED = Set.of(AMOUNT_CAPTURABLE_UPDATED, PAYMENT_FAILED);

    private static final Logger log = LoggerFactory.getLogger(PspWebhookService.class);

    public enum Outcome { PROCESSED, DUPLICATE, IGNORED }

    private final StripeSignatureVerifier verifier;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final PaymentTransactionRepository transactions;
    private final PaymentJdbcStore store;
    private final PaymentSagaService saga;
    private final PaymentEvents events;
    private final Clock clock;

    public PspWebhookService(StripeSignatureVerifier verifier, ObjectMapper mapper, PlatformTransactionManager txm,
            PaymentTransactionRepository transactions, PaymentJdbcStore store, PaymentSagaService saga,
            PaymentEvents events, Clock clock) {
        this.verifier = verifier;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txm);
        this.transactions = transactions;
        this.store = store;
        this.saga = saga;
        this.events = events;
        this.clock = clock;
    }

    /** @throws EcomException VALIDATION (-> 400) on a bad signature or body; UNAVAILABLE (-> 503, PSP retries) */
    public Outcome receive(byte[] rawBody, String signatureHeader) {
        try {
            verifier.verify(rawBody, signatureHeader);
        } catch (InvalidSignatureException e) {
            log.warn("Rejected PSP webhook: {}", e.getMessage());
            throw EcomException.validation("Invalid webhook signature");
        }
        JsonNode event = parse(rawBody);
        String eventId = event.path("id").asString("");
        String type = event.path("type").asString("");
        if (eventId.isBlank() || type.isBlank()) {
            throw EcomException.validation("Webhook event lacks id or type");
        }
        if (!HANDLED.contains(type)) {
            log.debug("Ignoring PSP webhook {} of type {}", eventId, type);
            return Outcome.IGNORED;
        }
        JsonNode intent = event.path("data").path("object");
        String pspReference = intent.path("id").asString("");
        if (pspReference.isBlank()) {
            throw EcomException.validation("Webhook event " + eventId + " lacks data.object.id");
        }
        return tx.execute(status -> process(eventId, type, pspReference, intent));
    }

    private Outcome process(String eventId, String type, String pspReference, JsonNode intent) {
        Optional<UUID> orderId = transactions.findOrderIdByPspReference(pspReference);
        if (orderId.isEmpty()) {
            String metadataOrderId = intent.path("metadata").path("order_id").asString("");
            if (!metadataOrderId.isBlank()) {
                // Stripe can deliver before the saga consumer commits the synchronous authorization
                throw EcomException.unavailable("Payment intent " + pspReference + " not recorded yet", null);
            }
            store.recordWebhookEvent(eventId, type, clock.instant());
            log.warn("PSP webhook {} for unknown payment intent {}; ignored", eventId, pspReference);
            return Outcome.IGNORED;
        }
        store.lockOrder(orderId.get());
        if (!store.recordWebhookEvent(eventId, type, clock.instant())) {
            log.info("Duplicate PSP webhook {}", eventId);
            return Outcome.DUPLICATE;
        }
        PaymentTransaction payment = transactions.findByOrderIdAndType(orderId.get(), TransactionType.AUTHORIZATION)
                .orElseThrow(() -> new IllegalStateException("Transaction vanished for order " + orderId.get()));
        if (payment.status() != TransactionStatus.REQUIRES_ACTION) {
            log.info("PSP webhook {} ({}) for payment {} already in {}; nothing to do",
                    eventId, type, payment.id(), payment.status());
            return Outcome.PROCESSED;
        }
        if (store.isOrderCancelled(payment.orderId())) {
            // late authorization rule: Order has already decided, so release whatever the PSP holds
            saga.voidAtPsp(payment);
            return Outcome.PROCESSED;
        }
        Instant now = clock.instant();
        if (AMOUNT_CAPTURABLE_UPDATED.equals(type)) {
            payment.markAuthorized(now);
            events.authorized(payment);
        } else {
            JsonNode error = intent.path("last_payment_error");
            String reason = firstNonBlank(
                    error.path("decline_code").asString(""), error.path("code").asString(""), "payment_failed");
            payment.markFailed(reason, now);
            events.failed(payment);
        }
        log.info("PSP webhook {} moved payment {} to {}", eventId, payment.id(), payment.status());
        return Outcome.PROCESSED;
    }

    private JsonNode parse(byte[] rawBody) {
        try {
            JsonNode node = mapper.readTree(rawBody);
            if (node == null || !node.isObject()) {
                throw EcomException.validation("Webhook body is not a JSON object");
            }
            return node;
        } catch (JacksonException _) {
            throw EcomException.validation("Webhook body is not valid JSON");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (!v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
