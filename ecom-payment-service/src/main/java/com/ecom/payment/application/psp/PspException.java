package com.ecom.payment.application.psp;

/** PSP call failed without a business outcome (network, 5xx, rate limit, misconfiguration). */
public class PspException extends RuntimeException {

    public PspException(String message) {
        super(message);
    }

    public PspException(String message, Throwable cause) {
        super(message, cause);
    }
}
