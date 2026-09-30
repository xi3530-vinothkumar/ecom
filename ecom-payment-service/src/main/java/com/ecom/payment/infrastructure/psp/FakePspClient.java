package com.ecom.payment.infrastructure.psp;

import com.ecom.payment.application.psp.AuthorizationResult;
import com.ecom.payment.application.psp.AuthorizationResult.CardDetails;
import com.ecom.payment.application.psp.PspClient;
import com.ecom.payment.application.psp.PspException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory PSP for local/e2e/tests. Outcome is deterministic by Stripe test token, references are
 * deterministic by idempotency key, and a repeated key replays the first response (as Stripe does)
 * without creating a second intent. Call counters let tests assert "exactly one authorize".
 */
public class FakePspClient implements PspClient {

    public static final String STATUS_REQUIRES_CAPTURE = "requires_capture";
    public static final String STATUS_REQUIRES_ACTION = "requires_action";
    public static final String STATUS_REQUIRES_PAYMENT_METHOD = "requires_payment_method";
    public static final String STATUS_CANCELED = "canceled";
    public static final String STATUS_SUCCEEDED = "succeeded";

    public static final String AUTHENTICATION_FAILED = "payment_intent_authentication_failure";

    private enum Behavior { AUTHORIZE, DECLINE, THREE_DS }

    private record TokenSpec(Behavior behavior, CardDetails card, String declineReason) {}

    private static final Map<String, TokenSpec> TOKENS = Map.of(
            "pm_card_visa", new TokenSpec(Behavior.AUTHORIZE, new CardDetails("visa", "4242"), null),
            "pm_card_mastercard", new TokenSpec(Behavior.AUTHORIZE, new CardDetails("mastercard", "4444"), null),
            "pm_card_chargeDeclined", new TokenSpec(Behavior.DECLINE, new CardDetails("visa", "0002"), "card_declined"),
            "pm_card_chargeDeclinedInsufficientFunds",
                    new TokenSpec(Behavior.DECLINE, new CardDetails("visa", "9995"), "insufficient_funds"),
            "pm_card_threeDSecure2Required", new TokenSpec(Behavior.THREE_DS, new CardDetails("visa", "3220"), null));

    /** Snapshot of a fake PaymentIntent. {@code orderId} mirrors Stripe's {@code metadata.order_id}. */
    public record Intent(String id, String orderId, long amountMinor, String currency, String paymentMethodRef,
            String status, String lastErrorCode) {

        Intent withStatus(String newStatus, String errorCode) {
            return new Intent(id, orderId, amountMinor, currency, paymentMethodRef, newStatus, errorCode);
        }
    }

    private record Request(long amountMinor, String currency, String paymentMethodRef) {}

    private record Replay(Request request, AuthorizationResult result) {}

    private final Map<String, Replay> byKey = new ConcurrentHashMap<>();
    private final Map<String, Intent> intents = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> authorizeCalls = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> cancelCalls = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> captureCalls = new ConcurrentHashMap<>();

    @Override
    public AuthorizationResult authorize(String idempotencyKey, long amountMinor, String currency, String paymentMethodRef) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        authorizeCalls.computeIfAbsent(idempotencyKey, k -> new AtomicInteger()).incrementAndGet();
        Request request = new Request(amountMinor, currency.toLowerCase(), paymentMethodRef);
        Replay replay = byKey.computeIfAbsent(idempotencyKey, key -> new Replay(request, create(key, request)));
        if (!replay.request().equals(request)) {
            throw new PspException("Idempotency key " + idempotencyKey + " reused with different parameters");
        }
        return replay.result();
    }

    private AuthorizationResult create(String key, Request request) {
        TokenSpec spec = TOKENS.get(request.paymentMethodRef());
        if (spec == null) {
            // Stripe rejects unknown payment methods before an intent exists
            return new AuthorizationResult.Declined(null, "invalid_payment_method", CardDetails.UNKNOWN);
        }
        String id = "pi_fake_" + digest("pi:" + key, 24);
        Intent intent = new Intent(id, key, request.amountMinor(), request.currency(), request.paymentMethodRef(), null, null);
        return switch (spec.behavior()) {
            case AUTHORIZE -> {
                intents.put(id, intent.withStatus(STATUS_REQUIRES_CAPTURE, null));
                yield new AuthorizationResult.Authorized(id, spec.card());
            }
            case DECLINE -> {
                intents.put(id, intent.withStatus(STATUS_REQUIRES_PAYMENT_METHOD, spec.declineReason()));
                yield new AuthorizationResult.Declined(id, spec.declineReason(), spec.card());
            }
            case THREE_DS -> {
                intents.put(id, intent.withStatus(STATUS_REQUIRES_ACTION, null));
                yield new AuthorizationResult.RequiresAction(id, id + "_secret_" + digest("cs:" + key, 16), spec.card());
            }
        };
    }

    @Override
    public void cancel(String pspReference) {
        cancelCalls.computeIfAbsent(pspReference, k -> new AtomicInteger()).incrementAndGet();
        intents.compute(pspReference, (id, intent) -> {
            if (intent == null) {
                throw new PspException("No such payment_intent: " + id);
            }
            return switch (intent.status()) {
                case STATUS_CANCELED -> intent;
                case STATUS_REQUIRES_ACTION, STATUS_REQUIRES_CAPTURE, STATUS_REQUIRES_PAYMENT_METHOD ->
                        intent.withStatus(STATUS_CANCELED, intent.lastErrorCode());
                default -> throw new PspException("Cannot cancel payment_intent " + id + " in status " + intent.status());
            };
        });
    }

    @Override
    public void capture(String pspReference) {
        captureCalls.computeIfAbsent(pspReference, k -> new AtomicInteger()).incrementAndGet();
        intents.compute(pspReference, (id, intent) -> {
            if (intent == null) {
                throw new PspException("No such payment_intent: " + id);
            }
            return switch (intent.status()) {
                case STATUS_SUCCEEDED -> intent;
                case STATUS_REQUIRES_CAPTURE -> intent.withStatus(STATUS_SUCCEEDED, null);
                default -> throw new PspException("Cannot capture payment_intent " + id + " in status " + intent.status());
            };
        });
    }

    /**
     * Simulates the customer finishing (or failing) the 3DS challenge in the browser.
     *
     * @throws IllegalStateException if the intent is not awaiting customer action
     */
    public Intent completeAction(String pspReference, boolean succeeded) {
        Intent updated = intents.computeIfPresent(pspReference, (id, intent) -> {
            if (!STATUS_REQUIRES_ACTION.equals(intent.status())) {
                throw new IllegalStateException("payment_intent " + id + " is " + intent.status() + ", not requires_action");
            }
            return succeeded
                    ? intent.withStatus(STATUS_REQUIRES_CAPTURE, null)
                    : intent.withStatus(STATUS_REQUIRES_PAYMENT_METHOD, AUTHENTICATION_FAILED);
        });
        if (updated == null) {
            throw new java.util.NoSuchElementException("No such payment_intent: " + pspReference);
        }
        return updated;
    }

    public Optional<Intent> intent(String pspReference) {
        return Optional.ofNullable(intents.get(pspReference));
    }

    public int authorizeCalls(String idempotencyKey) {
        return count(authorizeCalls, idempotencyKey);
    }

    public int cancelCalls(String pspReference) {
        return count(cancelCalls, pspReference);
    }

    public int captureCalls(String pspReference) {
        return count(captureCalls, pspReference);
    }

    private static int count(Map<String, AtomicInteger> counters, String key) {
        AtomicInteger c = counters.get(key);
        return c == null ? 0 : c.get();
    }

    private static String digest(String input, int hexChars) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, hexChars);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
