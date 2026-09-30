package com.ecom.notification.domain;

/** Masks contact details for logs and the audit table; the raw address is never persisted here. */
public final class RecipientMasking {

    public static String mask(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return "";
        }
        String r = recipient.trim();
        int at = r.lastIndexOf('@');
        if (at > 0) {
            String local = r.substring(0, at);
            String domain = r.substring(at + 1);
            return (local.length() == 1 ? "*" : local.charAt(0) + "***") + "@" + domain;
        }
        // phone or other identifier: keep only the last two characters
        int keep = Math.min(2, r.length() - 1);
        return "*".repeat(Math.max(3, r.length() - keep)) + r.substring(r.length() - keep);
    }

    private RecipientMasking() {}
}
