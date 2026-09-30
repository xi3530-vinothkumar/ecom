package com.ecom.notification.domain;

/** A rendered message ready for a sender. {@code toString} masks the recipient and omits the body. */
public record OutgoingNotification(Channel channel, String recipient, String templateKey, String subject, String body) {

    public String maskedRecipient() {
        return RecipientMasking.mask(recipient);
    }

    @Override
    public String toString() {
        return "OutgoingNotification[" + channel + ", " + templateKey + " -> " + maskedRecipient() + "]";
    }
}
