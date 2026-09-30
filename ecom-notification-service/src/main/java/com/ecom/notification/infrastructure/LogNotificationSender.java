package com.ecom.notification.infrastructure;

import com.ecom.notification.domain.OutgoingNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default for local/dev: "delivers" by logging. Recipient masked; subject/body not logged (may hold PII). */
public class LogNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationSender.class);

    @Override
    public void send(OutgoingNotification n) {
        log.info("Notification {} via {} to {} ({} chars)", n.templateKey(), n.channel(), n.maskedRecipient(),
                n.body().length());
    }
}
