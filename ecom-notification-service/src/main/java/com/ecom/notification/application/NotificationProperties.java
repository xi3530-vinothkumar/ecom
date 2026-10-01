package com.ecom.notification.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param channel {@code log} | {@code smtp}
 */
@ConfigurationProperties("ecom.notification")
public record NotificationProperties(
        @DefaultValue("log") String channel,
        @DefaultValue("no-reply@ecom.local") String mailFrom) {}
