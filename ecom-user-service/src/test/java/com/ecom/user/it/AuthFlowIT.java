package com.ecom.user.it;

import static com.ecom.testing.RestCalls.exchange;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.common.events.EventTopics;
import com.ecom.user.application.AdminBootstrap;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AuthFlowIT extends AbstractIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired AdminBootstrap adminBootstrap;

    @Test
    void registerWritesUserRegisteredOutboxRowWithoutPii() throws Exception {
        String email = uniqueEmail();
        UUID userId = register(email, "correct-horse-1");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT topic, payload FROM outbox WHERE message_key = ?", userId.toString());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("topic")).isEqualTo(EventTopics.USER_REGISTERED);
        String payload = (String) rows.getFirst().get("payload");
        JsonNode envelope = mapper.readTree(payload);
        assertThat(envelope.path("payload").path("userId").asString()).isEqualTo(userId.toString());
        assertThat(payload).doesNotContain(email).doesNotContain("Test User");

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
        assertThat(hash).startsWith("$argon2id$");
    }

    @Test
    void duplicateEmailIsConflictCaseInsensitively() {
        String email = uniqueEmail();
        register(email, "correct-horse-1");

        var res = post("/users", Map.of("email", email.toUpperCase(), "password", "another-pass-1", "fullName", "X"),
                new HttpHeaders());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void invalidRegistrationIsBadRequest() {
        var res = post("/users", Map.of("email", "not-an-email", "password", "short", "fullName", ""), new HttpHeaders());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void loginIssuesAccessTokenAndHardenedRefreshCookie() {
        String email = uniqueEmail();
        register(email, "correct-horse-1");

        var res = login(email.toUpperCase(), "correct-horse-1");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("tokenType", "Bearer").containsKey("accessToken")
                .doesNotContainKey("refreshToken");
        assertThat(((Number) res.getBody().get("expiresIn")).longValue()).isBetween(890L, 900L);
        String setCookie = res.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("refresh_token=", "HttpOnly", "Secure", "SameSite=Strict", "Path=/auth");
        assertThat(refreshCookie(res).getMaxAge()).isBetween(14L * 86400 - 60, 14L * 86400);
    }

    @Test
    void wrongPasswordAndUnknownEmailGetSameGeneric401() {
        String email = uniqueEmail();
        register(email, "correct-horse-1");

        var wrong = login(email, "wrong-password");
        var unknown = login(uniqueEmail(), "whatever-pass");

        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrong.getBody().get("detail")).isEqualTo(unknown.getBody().get("detail"));
    }

    @Test
    void refreshRotatesAndReuseRevokesFamily() {
        String email = uniqueEmail();
        register(email, "correct-horse-1");
        String first = refreshCookie(login(email, "correct-horse-1")).getValue();

        var rotated = withRefreshCookie("/auth/refresh", first);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rotated.getBody()).containsKey("accessToken");
        String second = refreshCookie(rotated).getValue();
        assertThat(second).isNotEqualTo(first);

        // attacker replays the rotated token -> 401 and the whole family dies
        assertThat(withRefreshCookie("/auth/refresh", first).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(withRefreshCookie("/auth/refresh", second).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // other sessions (families) of the same user are unaffected
        String otherSession = refreshCookie(login(email, "correct-horse-1")).getValue();
        assertThat(withRefreshCookie("/auth/refresh", otherSession).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void onlyHashOfRefreshTokenIsStored() {
        String email = uniqueEmail();
        UUID userId = register(email, "correct-horse-1");
        String raw = refreshCookie(login(email, "correct-horse-1")).getValue();

        List<String> hashes = jdbc.queryForList(
                "SELECT token_hash FROM refresh_tokens WHERE user_id = ?", String.class, userId);
        assertThat(hashes).hasSize(1).doesNotContain(raw);
        assertThat(hashes.getFirst()).hasSize(64);
    }

    @Test
    void logoutRevokesFamilyAndClearsCookie() {
        String email = uniqueEmail();
        register(email, "correct-horse-1");
        String token = refreshCookie(login(email, "correct-horse-1")).getValue();

        var out = withRefreshCookie("/auth/logout", token);
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refreshCookie(out).getMaxAge()).isZero();

        assertThat(withRefreshCookie("/auth/refresh", token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // idempotent
        assertThat(withRefreshCookie("/auth/logout", token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void refreshWithoutOrWithGarbageTokenIs401() {
        var none = post("/auth/refresh", null, new HttpHeaders());
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(withRefreshCookie("/auth/refresh", "garbage").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void fiveFailuresLockTheAccountEvenForTheRightPassword() {
        String email = uniqueEmail();
        register(email, "correct-horse-1");
        String bystander = uniqueEmail();
        register(bystander, "correct-horse-1");

        for (int i = 0; i < 5; i++) {
            assertThat(login(email, "wrong-" + i).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        var locked = login(email, "correct-horse-1");
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(locked.getBody().get("detail")).isEqualTo(login(uniqueEmail(), "x-password").getBody().get("detail"));

        assertThat(login(bystander, "correct-horse-1").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void jwksVerifiesIssuedAccessToken() throws Exception {
        String email = uniqueEmail();
        UUID userId = register(email, "correct-horse-1");
        String accessToken = (String) login(email, "correct-horse-1").getBody().get("accessToken");

        ResponseEntity<String> jwks =
                exchange(http, HttpMethod.GET, "/.well-known/jwks.json", null, new HttpHeaders(), String.class);
        assertThat(jwks.getStatusCode()).isEqualTo(HttpStatus.OK);
        JWKSet set = JWKSet.parse(jwks.getBody());
        assertThat(jwks.getBody()).doesNotContain("\"d\"");

        SignedJWT jwt = SignedJWT.parse(accessToken);
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        RSAKey key = (RSAKey) set.getKeyByKeyId(jwt.getHeader().getKeyID());
        assertThat(key).isNotNull();
        assertThat(jwt.verify(new RSASSAVerifier(key.toRSAPublicKey()))).isTrue();

        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.getIssuer()).isEqualTo("ecom-user-service");
        assertThat(claims.getAudience()).containsExactly("ecom");
        assertThat(claims.getStringListClaim("roles")).containsExactly("CUSTOMER");
        assertThat(claims.getExpirationTime()).isAfter(new Date());
        assertThat(claims.getJWTID()).isNotBlank();
    }

    @Test
    void loginWithGuestCartMergesCartAsTheAuthenticatedUser() {
        String email = uniqueEmail();
        UUID userId = register(email, "correct-horse-1");
        String guestCartId = UUID.randomUUID().toString();

        var res = post("/auth/login", Map.of("email", email, "password", "correct-horse-1", "guestCartId", guestCartId),
                new HttpHeaders());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(MERGE_CALLS).anySatisfy(c -> {
            assertThat(c.request().getGuestCartId()).isEqualTo(guestCartId);
            assertThat(c.request().getUserId()).isEqualTo(userId.toString());
            assertThat(c.propagatedUserId()).isEqualTo(userId.toString());
        });
    }

    @Test
    void bootstrapAdminCanLoginWithAdminRoleAndBootstrapIsIdempotent() throws Exception {
        adminBootstrap.run(new DefaultApplicationArguments());
        adminBootstrap.run(new DefaultApplicationArguments());

        Integer admins = jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, ADMIN_EMAIL);
        assertThat(admins).isEqualTo(1);
        var res = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        var roles = SignedJWT.parse((String) res.getBody().get("accessToken")).getJWTClaimsSet().getStringListClaim("roles");
        assertThat(roles).containsExactlyInAnyOrder("ADMIN", "CUSTOMER");
    }
}
