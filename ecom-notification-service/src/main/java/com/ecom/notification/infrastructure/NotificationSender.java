package com.ecom.notification.infrastructure;

import com.ecom.notification.domain.OutgoingNotification;

/** Delivers one message. Must throw on failure so the event is retried (and dead-lettered eventually). */
public interface NotificationSender {

    void send(OutgoingNotification notification);
}
