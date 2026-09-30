package com.ecom.payment.domain;

public enum TransactionStatus {
    REQUIRES_ACTION,
    AUTHORIZED,
    FAILED,
    VOIDED,
    CAPTURED;

    /** Holds (or may still obtain) funds at the PSP, so a cancel decision must void it. */
    public boolean isVoidable() {
        return this == REQUIRES_ACTION || this == AUTHORIZED;
    }
}
