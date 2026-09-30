package com.ecom.notification.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ecom.notification")
public class NotificationProperties {

    /** log | smtp. */
    private String channel = "log";
    private String mailFrom = "no-reply@ecom.local";

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getMailFrom() {
        return mailFrom;
    }

    public void setMailFrom(String mailFrom) {
        this.mailFrom = mailFrom;
    }
}
