package com.ecom.notification.domain;

public final class TemplateKeys {

    public static final String WELCOME = "WELCOME";
    public static final String ORDER_CONFIRMED = "ORDER_CONFIRMED";
    private static final String ORDER_CANCELLED_PREFIX = "ORDER_CANCELLED_";

    /** OUT_OF_STOCK | PAYMENT_DECLINED | PAYMENT_TIMEOUT -> ORDER_CANCELLED_<reason>. */
    public static String orderCancelled(String reason) {
        if (reason == null || !reason.matches("[A-Z_]+")) {
            throw new IllegalArgumentException("Unsupported cancel reason: " + reason);
        }
        return ORDER_CANCELLED_PREFIX + reason;
    }

    private TemplateKeys() {}
}
