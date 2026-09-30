package com.ecom.user.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Result of a successful login/refresh. {@code refreshToken} is the raw value: never log or persist it. */
public record IssuedTokens(
        UUID userId,
        Set<String> roles,
        String accessToken,
        long accessTokenExpiresInSeconds,
        String refreshToken,
        Instant refreshTokenExpiresAt) {

    @Override
    public String toString() {
        return "IssuedTokens[userId=" + userId + ", roles=" + roles + "]";
    }
}
