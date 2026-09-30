package com.ecom.payment.domain;

public class IllegalTransitionException extends IllegalStateException {

    public IllegalTransitionException(TransactionStatus from, TransactionStatus to) {
        super("Illegal payment transaction transition " + from + " -> " + to);
    }
}
