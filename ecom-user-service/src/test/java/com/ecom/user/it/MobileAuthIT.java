package com.ecom.user.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class MobileAuthIT extends AbstractIT {

    private ResponseEntity<Map<String, Object>> mobileLogin(String email, String password) {
        return post("/auth/mobile/login", Map.of("email", email, "password", password), new HttpHeaders());
    }

    private ResponseEntity<Map<String, Object>> mobile(String path, String refreshToken) {
        return post(path, Map.of("refreshToken", refreshToken), new HttpHeaders());
    }

    private static String refreshToken(ResponseEntity<Map<String, Object>> res) {
        return (String) res.getBody().get("refreshToken");
    }

    @Test
    void loginReturnsBothTokensInBodyAndSetsNoCookie() {
        String email = uniqueEmail();
        register(email, "correct-horse-1");

        var res = mobileLogin(email, "correct-horse-1");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("tokenType", "Bearer").containsKeys("accessToken", "refreshToken");
        assertThat(((Number) res.getBody().get("expiresIn")).longValue()).isBetween(890L, 900L);
        assertThat(((Number) res.getBody().get("refreshExpiresIn")).longValue()).isBetween(14L * 86400 - 60, 14L * 86400);
        assertThat(res.getHeaders().get(HttpHeaders.SET_COOKIE)).isNullOrEmpty();
        assertThat(res.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void wrongPasswordIsSameGeneric401AsBrowserLogin() {
        String email = uniqueEmail();
        register(email, "correct-horse-1");

        var mobile = mobileLogin(email, "wrong-password");

        assertThat(mobile.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mobile.getBody().get("detail")).isEqualTo(login(email, "wrong-password").getBody().get("detail"));
    }

    @Test
    void refreshRotatesAndReuseRevokesFamily() {
        String email = uniqueEmail();
        register(email, "correct-horse-1");
        String first = refreshToken(mobileLogin(email, "correct-horse-1"));

        var rotated = mobile("/auth/mobile/refresh", first);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rotated.getHeaders().get(HttpHeaders.SET_COOKIE)).isNullOrEmpty();
        String second = refreshToken(rotated);
        assertThat(second).isNotBlank().isNotEqualTo(first);

        assertThat(mobile("/auth/mobile/refresh", first).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mobile("/auth/mobile/refresh", second).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutRevokesFamilyAndIsIdempotent() {
        String email = uniqueEmail();
        register(email, "correct-horse-1");
        String token = refreshToken(mobileLogin(email, "correct-horse-1"));

        var out = mobile("/auth/mobile/logout", token);
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(out.getHeaders().get(HttpHeaders.SET_COOKIE)).isNullOrEmpty();

        assertThat(mobile("/auth/mobile/refresh", token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mobile("/auth/mobile/logout", token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void missingRefreshTokenIsBadRequest() {
        assertThat(post("/auth/mobile/refresh", Map.of(), new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/auth/mobile/logout", Map.of(), new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void requestsWithOriginAreRefused() {
        String email = uniqueEmail();
        register(email, "correct-horse-1");
        HttpHeaders browser = new HttpHeaders();
        browser.setOrigin("http://localhost:3000");

        var res = post("/auth/mobile/login", Map.of("email", email, "password", "correct-horse-1"), browser);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).doesNotContainKey("refreshToken");
    }

    @Test
    void browserCookieCannotBeTradedForABodyToken() {
        String email = uniqueEmail();
        register(email, "correct-horse-1");
        String cookieToken = refreshCookie(login(email, "correct-horse-1")).getValue();

        // the /auth-scoped cookie reaches /auth/mobile/** but must be ignored there
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, "refresh_token=" + cookieToken);
        var res = post("/auth/mobile/refresh", Map.of(), h);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).doesNotContainKey("refreshToken");

        // and the browser session is still intact
        assertThat(withRefreshCookie("/auth/refresh", cookieToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void browserRefreshIgnoresBodyToken() {
        String email = uniqueEmail();
        register(email, "correct-horse-1");
        String mobileToken = refreshToken(mobileLogin(email, "correct-horse-1"));

        var res = post("/auth/refresh", Map.of("refreshToken", mobileToken), new HttpHeaders());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mobile("/auth/mobile/refresh", mobileToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
