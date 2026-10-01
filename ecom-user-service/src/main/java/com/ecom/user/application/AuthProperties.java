package com.ecom.user.application;

import com.ecom.user.domain.LockoutPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ecom.auth")
public record AuthProperties(
        @DefaultValue("false") boolean ephemeralJwtKey,
        @DefaultValue("14d") Duration refreshTokenTtl,
        /** Absolute session (family) lifetime from login; refreshing never extends past it. */
        @DefaultValue("30d") Duration sessionMaxLifetime,
        @DefaultValue("refresh_token") String refreshCookieName,
        @DefaultValue("/auth") String refreshCookiePath,
        String adminEmail,
        String adminPassword,
        @DefaultValue Lockout lockout) {

    public record Lockout(
            @DefaultValue("5") int maxFailures,
            @DefaultValue("15m") Duration window,
            @DefaultValue("15m") Duration duration) {

        public LockoutPolicy toPolicy() {
            return new LockoutPolicy(maxFailures, window, duration);
        }
    }

    public boolean bootstrapAdminConfigured() {
        return adminEmail != null && !adminEmail.isBlank() && adminPassword != null && !adminPassword.isBlank();
    }
}
