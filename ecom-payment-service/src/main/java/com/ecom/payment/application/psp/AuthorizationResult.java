package com.ecom.payment.application.psp;

import java.util.Objects;

public sealed interface AuthorizationResult {

    /** PSP-side display metadata of the tokenized method; either field may be null. */
    CardDetails card();

    record Authorized(String pspReference, CardDetails card) implements AuthorizationResult {
        public Authorized {
            Objects.requireNonNull(pspReference, "pspReference");
            card = card == null ? CardDetails.UNKNOWN : card;
        }
    }

    /** {@code pspReference} may be null when the PSP rejected the request before creating an intent. */
    record Declined(String pspReference, String reason, CardDetails card) implements AuthorizationResult {
        public Declined {
            Objects.requireNonNull(reason, "reason");
            card = card == null ? CardDetails.UNKNOWN : card;
        }
    }

    record RequiresAction(String pspReference, String clientSecret, CardDetails card) implements AuthorizationResult {
        public RequiresAction {
            Objects.requireNonNull(pspReference, "pspReference");
            Objects.requireNonNull(clientSecret, "clientSecret");
            card = card == null ? CardDetails.UNKNOWN : card;
        }
    }

    record CardDetails(String brand, String last4) {
        public static final CardDetails UNKNOWN = new CardDetails(null, null);
    }
}
