package com.ecom.user.web;

import com.ecom.platform.core.EcomException;
import com.ecom.user.application.AuthService;
import com.ecom.user.application.CartMergeService;
import com.ecom.user.application.IssuedTokens;
import com.ecom.user.web.AuthDtos.LoginRequest;
import com.ecom.user.web.AuthDtos.MobileRefreshRequest;
import com.ecom.user.web.AuthDtos.MobileTokenResponse;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Native (mobile/CLI) clients that keep the refresh token in OS secure storage. The token travels
 * only in JSON bodies; no cookie is read or set. The browser refresh cookie is scoped to
 * {@code /auth} and therefore also reaches these paths: it is deliberately ignored, otherwise script
 * on a page could trade the HttpOnly cookie for a body-readable token.
 *
 * <p>Browsers always send {@code Origin} on POST; native HTTP clients do not. Requests carrying it are
 * refused so these endpoints cannot be driven from a web page.
 */
@RestController
@RequestMapping("/auth/mobile")
class MobileAuthController {

    private final AuthService auth;
    private final CartMergeService cartMerge;
    private final Clock clock;

    MobileAuthController(AuthService auth, CartMergeService cartMerge, Clock clock) {
        this.auth = auth;
        this.cartMerge = cartMerge;
        this.clock = clock;
    }

    @PostMapping("/login")
    ResponseEntity<MobileTokenResponse> login(
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
            @Valid @RequestBody LoginRequest req) {
        rejectBrowser(origin);
        IssuedTokens tokens = auth.login(req.email(), req.password());
        cartMerge.mergeBestEffort(req.guestCartId(), tokens.userId(), tokens.roles());
        return respond(tokens);
    }

    @PostMapping("/refresh")
    ResponseEntity<MobileTokenResponse> refresh(
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
            @Valid @RequestBody MobileRefreshRequest req) {
        rejectBrowser(origin);
        return respond(auth.refresh(req.refreshToken()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
            @Valid @RequestBody MobileRefreshRequest req) {
        rejectBrowser(origin);
        auth.logout(req.refreshToken());
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private static void rejectBrowser(String origin) {
        if (origin != null) {
            throw EcomException.forbidden("Mobile auth endpoints are not available to browsers");
        }
    }

    private ResponseEntity<MobileTokenResponse> respond(IssuedTokens t) {
        long refreshExpiresIn = Math.max(0, Duration.between(clock.instant(), t.refreshTokenExpiresAt()).toSeconds());
        var body = new MobileTokenResponse(
                t.accessToken(), t.accessTokenExpiresInSeconds(), "Bearer", t.refreshToken(), refreshExpiresIn);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
