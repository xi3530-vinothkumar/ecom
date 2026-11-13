package com.ecom.notification.infrastructure;

import com.ecom.notification.application.NotificationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/** NOTIFICATION_CHANNEL=log (default) or smtp. */
@Configuration
public class NotificationSenderConfig {

    @Bean
    @ConditionalOnProperty(prefix = "ecom.notification", name = "channel", havingValue = "log", matchIfMissing = true)
    NotificationSender logNotificationSender() {
        return new LogNotificationSender();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ecom.notification", name = "channel", havingValue = "smtp")
    NotificationSender smtpNotificationSender(JavaMailSender mail, NotificationProperties props) {
        return new SmtpNotificationSender(mail, props.mailFrom());
    }
}
