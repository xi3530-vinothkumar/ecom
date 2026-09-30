package com.ecom.payment.application.psp;

/**
 * Payment service provider port. Implementations must be idempotent per {@code idempotencyKey}: the
 * same key always yields the same outcome and never a second authorization (PLAN §12).
 */
public interface PspClient {

    /**
     * Creates and confirms a manual-capture authorization.
     *
     * @param idempotencyKey the order id; also sent as PSP metadata so webhooks can be correlated
     * @throws PspException on transport/PSP errors that are not a customer-facing decline
     */
    AuthorizationResult authorize(String idempotencyKey, long amountMinor, String currency, String paymentMethodRef);

    /** Releases an uncaptured authorization. Cancelling an already cancelled intent succeeds. */
    void cancel(String pspReference);

    /** Captures an authorization in full. Capturing an already captured intent succeeds. */
    void capture(String pspReference);
}
