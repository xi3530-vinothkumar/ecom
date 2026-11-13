package com.ecom.user.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

final class AuthDtos {

    record LoginRequest(
            @NotBlank @Size(max = 320) String email,
            @NotBlank @Size(max = 1024) String password,
            @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]*$") String guestCartId) {
        @Override
        public String toString() {
            return "LoginRequest[email=" + email + "]";
        }
    }

    /** Browser response: the refresh token travels only in the HttpOnly cookie. */
    record TokenResponse(String accessToken, long expiresIn, String tokenType) {}

    record MobileRefreshRequest(@NotBlank @Size(max = 256) String refreshToken) {
        @Override
        public String toString() {
            return "MobileRefreshRequest[***]";
        }
    }

    /** Mobile response: the refresh token travels only in the body; no cookie is set. */
    record MobileTokenResponse(
            String accessToken, long expiresIn, String tokenType, String refreshToken, long refreshExpiresIn) {
        @Override
        public String toString() {
            return "MobileTokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
        }
    }

    private AuthDtos() {}
}
