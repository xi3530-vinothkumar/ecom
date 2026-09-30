package com.ecom.user.web;

import com.ecom.user.application.AuthProperties;
import com.ecom.user.application.AuthService;
import com.ecom.user.application.CartMergeService;
import com.ecom.user.application.IssuedTokens;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

@RestController
@RequestMapping("/auth")
class AuthController {

    record LoginRequest(
            @NotBlank @Size(max = 320) String email,
            @NotBlank @Size(max = 1024) String password,
            @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]*$") String guestCartId) {
        @Override
        public String toString() {
            return "LoginRequest[email=" + email + "]";
        }
    }

    /** Body fallback for non-browser clients; only honored when refresh-token-in-body is enabled. */
    record RefreshRequest(@Size(max = 256) String refreshToken) {
        @Override
        public String toString() {
            return "RefreshRequest[***]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TokenResponse(String accessToken, long expiresIn, String tokenType, String refreshToken) {}

    private final AuthService auth;
    private final CartMergeService cartMerge;
    private final AuthProperties props;
    private final Clock clock;

    AuthController(AuthService auth, CartMergeService cartMerge, AuthProperties props, Clock clock) {
        this.auth = auth;
        this.cartMerge = cartMerge;
        this.props = props;
        this.clock = clock;
    }

    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        IssuedTokens tokens = auth.login(req.email(), req.password());
        cartMerge.mergeBestEffort(req.guestCartId(), tokens.userId(), tokens.roles());
        return respond(tokens);
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(
            HttpServletRequest request, @Valid @RequestBody(required = false) RefreshRequest body) {
        return respond(auth.refresh(presentedToken(request, body)));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, @Valid @RequestBody(required = false) RefreshRequest body) {
        auth.logout(presentedToken(request, body));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private String presentedToken(HttpServletRequest request, RefreshRequest body) {
        Cookie cookie = WebUtils.getCookie(request, props.refreshCookieName());
        if (cookie != null && !cookie.getValue().isBlank()) {
            return cookie.getValue();
        }
        return props.refreshTokenInBody() && body != null ? body.refreshToken() : null;
    }

    private ResponseEntity<TokenResponse> respond(IssuedTokens t) {
        Duration cookieTtl = Duration.between(clock.instant(), t.refreshTokenExpiresAt());
        var body = new TokenResponse(
                t.accessToken(), t.accessTokenExpiresInSeconds(), "Bearer",
                props.refreshTokenInBody() ? t.refreshToken() : null);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(t.refreshToken(), cookieTtl).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(props.refreshCookieName(), value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(props.refreshCookiePath())
                .maxAge(maxAge)
                .build();
    }
}
