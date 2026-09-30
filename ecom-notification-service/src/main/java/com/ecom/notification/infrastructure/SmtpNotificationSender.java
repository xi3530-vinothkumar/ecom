package com.ecom.notification.infrastructure;

import com.ecom.notification.domain.Channel;
import com.ecom.notification.domain.OutgoingNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

public class SmtpNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpNotificationSender.class);

    private final JavaMailSender mail;
    private final String from;

    public SmtpNotificationSender(JavaMailSender mail, String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override
    public void send(OutgoingNotification n) {
        if (n.channel() != Channel.EMAIL) {
            throw new IllegalArgumentException("SMTP sender cannot deliver " + n.channel());
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(n.recipient());
        msg.setSubject(n.subject());
        msg.setText(n.body());
        mail.send(msg);
        log.info("Sent {} email to {}", n.templateKey(), n.maskedRecipient());
    }
}
