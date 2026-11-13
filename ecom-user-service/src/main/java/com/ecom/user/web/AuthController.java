package com.ecom.user.web;

import com.ecom.user.application.AuthProperties;
import com.ecom.user.application.AuthService;
import com.ecom.user.application.CartMergeService;
import com.ecom.user.application.IssuedTokens;
import com.ecom.user.web.AuthDtos.LoginRequest;
import com.ecom.user.web.AuthDtos.TokenResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

/**
 * Browser clients. The refresh token only ever travels in the HttpOnly cookie, never in a body, so
 * script on the page (XSS) can neither read it nor obtain it from a response. Native clients use
 * {@link MobileAuthController}.
 */
@RestController
@RequestMapping("/auth")
class AuthController {

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
    ResponseEntity<TokenResponse> refresh(HttpServletRequest request) {
        return respond(auth.refresh(cookieToken(request)));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(cookieToken(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private String cookieToken(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, props.refreshCookieName());
        return cookie != null ? cookie.getValue() : null;
    }

    private ResponseEntity<TokenResponse> respond(IssuedTokens t) {
        Duration cookieTtl = Duration.between(clock.instant(), t.refreshTokenExpiresAt());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(t.refreshToken(), cookieTtl).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new TokenResponse(t.accessToken(), t.accessTokenExpiresInSeconds(), "Bearer"));
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
