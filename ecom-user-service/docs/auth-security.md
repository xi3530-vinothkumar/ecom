# user-service — Authentication & Session Security

How login, refresh, and logout work in `ecom-user-service`, what protects them, and the known gaps.
Reviewed 2026-10-01 against branch `feat/spring-boot-4`.

Related: [`openapi.yaml`](openapi.yaml) (HTTP contract, Postman import).

---

## 1. Flow overview

```
Client ──HTTPS──▶ Gateway (:8080) ──HTTP──▶ user-service (:8081)
                  │ verifies JWT (RS256, JWKS)        │ trusts X-User-Id / X-User-Roles
                  │ strips client X-User-* headers    │ (NetworkPolicy: HTTP ingress from Gateway only)
                  │ stamps X-User-* from claims       │
                  │ per-IP login rate limit           │
```

| Endpoint | Auth | What it does |
|---|---|---|
| Endpoint | Client | Refresh token travels in | What it does |
|---|---|---|---|
| `POST /auth/login` | browser | `Set-Cookie` only | Verifies email + password, issues access JWT (15 min) + refresh token (14 d), best-effort guest-cart merge |
| `POST /auth/refresh` | browser | cookie only (body ignored) | Rotates the refresh token, issues a new access JWT |
| `POST /auth/logout` | browser | cookie only | Revokes the whole token family, clears the cookie, always `204` |
| `POST /auth/mobile/login` | native | response body only | Same as browser login; no cookie set |
| `POST /auth/mobile/refresh` | native | request + response body only (cookie ignored) | Rotates the refresh token |
| `POST /auth/mobile/logout` | native | request body only | Revokes the whole token family, `204` (also for unknown tokens) |
| `GET /.well-known/jwks.json` | Gateway | — | Public signing keys; internal only, not routed publicly |

Code: `web/AuthController.java` (browser), `web/MobileAuthController.java` (native),
`application/AuthService.java`, `application/TokenIssuer.java`. Both controllers share `AuthService`,
so lockout, rotation, and reuse detection behave identically.

---

## 2. Refresh-token model (`refresh_tokens` table)

Each row is one refresh token. Tokens are grouped into **families**: one family = one login session
on one device. Schema: `db/migration/V1__init.sql`.

| Column | Purpose |
|---|---|
| `id` | Row id; target of `replaced_by` |
| `user_id` | Owner; `ON DELETE CASCADE` |
| `token_hash` | SHA-256 hex of the raw token. The raw token (32 random bytes, base64url) is only ever sent to the client, so a DB leak yields nothing replayable. `UNIQUE` — it is the lookup key |
| `family_id` | Session id. New random UUID at login; every refresh keeps the same family. Logout and theft detection revoke by family (`idx_refresh_tokens_family`) |
| `created_at` | Issue time (audit) |
| `expires_at` | `created_at + 14d`; past this the token is EXPIRED. Used by cleanup (`idx_refresh_tokens_expires_at`) |
| `revoked_at` | `null` = usable. Set on rotation, logout, or family revocation |
| `replaced_by` | `id` of the successor token. Together with `revoked_at` tells *why* the token is dead |

### Status derivation (`RefreshToken.statusAt`)

| `revoked_at` | `replaced_by` | time | Status |
|---|---|---|---|
| null | — | `now < expires_at` | ACTIVE |
| null | — | `now >= expires_at` | EXPIRED |
| set | set | — | ROTATED (already exchanged) |
| set | null | — | REVOKED (logout / family kill) |

### Lifecycle

```
login             → T1 {family F, active}
refresh(T1)       → T1 {revoked_at=now, replaced_by=T2};  T2 {family F, active}
refresh(T2)       → T2 rotated → T3 active
refresh(T1) again → T1 is ROTATED → reuse detected → revokeFamily(F) → T3 dead too → 401
logout(T3)        → revokeFamily(F); replaced_by stays null → REVOKED
```

**Reuse detection.** A rotated token must never be presented again. If it is, two parties hold the
session (e.g. an attacker replaying a stolen token). The service cannot tell which is legitimate, so it
kills the whole family and both must log in again. `findByTokenHashForUpdate` takes a
`PESSIMISTIC_WRITE` row lock so two concurrent refreshes of the same token serialize — the loser sees
ROTATED.

**Cleanup.** `RefreshTokenCleanup` runs hourly (`ecom.auth.cleanup-interval`, default `PT1H`) and deletes
whole families whose newest token expired more than one day ago. Rotated rows are kept while the family
is alive because reuse detection depends on them.

---

## 3. Why the refresh token travels in a cookie, not the JSON body

Short answer: an `HttpOnly` cookie is unreadable by JavaScript, so an XSS bug cannot steal it.

### Body vs cookie

**If it were in the JSON body** (`{ "refreshToken": "..." }`):
- The frontend must store it somewhere JavaScript can reach (`localStorage` or a JS variable).
- Any XSS — in our code, a third-party script, or a compromised npm dependency — can read it and send
  it to an attacker.
- The attacker then holds a 14-day, self-renewing credential: effectively a full account takeover.

**As a cookie** (`Set-Cookie: refresh_token=...; HttpOnly; Secure; SameSite=Strict; Path=/auth`):

| Attribute | Protects against |
|---|---|
| `HttpOnly` | `document.cookie` cannot see it → XSS cannot read or exfiltrate it |
| `Secure` | Sent only over HTTPS → not sniffable on the network |
| `SameSite=Strict` | Browser does not attach it to cross-site requests → no CSRF on `/auth/refresh` / `/auth/logout` |
| `Path=/auth` | Sent only to `/auth/*`, not with every API call → less exposure in logs and proxies |

The browser stores and resends the cookie automatically; frontend code never touches it.

### Why the access token *is* in the body

- It must be sent as `Authorization: Bearer ...`, so JavaScript has to read it.
- It lives only 15 minutes, so an XSS-stolen access token has a bounded blast radius.
- The frontend should keep it **in memory only** (not `localStorage`). After a page reload it calls
  `/auth/refresh`; the cookie is sent automatically and a new access token comes back.

The split is deliberate: the long-lived token that can mint new tokens is kept away from JavaScript;
the short-lived token that JavaScript needs is low-value.

### Trade-offs of the cookie

- **CSRF must be handled.** Covered by `SameSite=Strict` plus the Gateway's CORS origin allow-list.
- **Credentialed CORS.** The SPA must call with `credentials: 'include'`; CORS cannot use a `*` origin
  (`SecurityConfiguration.java`, `setAllowCredentials(true)`).
- **Non-browser clients** (mobile apps, Postman, CLIs, `Secure` cookies over plain http in local dev)
  cannot use the cookie easily. They use the dedicated `/auth/mobile/*` endpoints (§3a).

A mobile app stores the token in the OS keychain/keystore. Browsers have no equivalent safe storage,
so they use the cookie.

### 3a. Dedicated mobile endpoints

The earlier design used one global flag (`REFRESH_TOKEN_IN_BODY`) that put the refresh token in the
body for *every* client. Enabling it for mobile would have exposed browser sessions to XSS, so it was
removed and replaced by separate endpoints.

**Why not let the client choose per request** (e.g. `X-Client-Type: mobile`)? XSS could do:

```js
fetch('/auth/refresh', { method: 'POST', credentials: 'include',
                         headers: { 'X-Client-Type': 'mobile' } })
```

The browser attaches the HttpOnly cookie automatically and the server would answer with the new
refresh token in a readable body — the token is stolen without ever reading the cookie.

**Rule: a token is answered in the channel it arrived on.**

| Path | Reads refresh token from | Returns refresh token in |
|---|---|---|
| `/auth/*` | cookie only — body ignored | `Set-Cookie` only |
| `/auth/mobile/*` | body only — cookie ignored | body only, no `Set-Cookie` |

Script on a page can make the browser *send* the cookie but can never *read* it, so it cannot obtain
the raw token needed to use the mobile endpoints. The refresh cookie's `Path=/auth` means it also
reaches `/auth/mobile/*`; `MobileAuthController` deliberately never reads it (covered by
`MobileAuthIT.browserCookieCannotBeTradedForABodyToken`).

**Defence in depth:** browsers always send `Origin` on POST; native HTTP clients do not. Mobile
endpoints reject any request carrying `Origin` with `403`, so they cannot be driven from a web page
at all. A hybrid/WebView app that sends `Origin` must use the browser endpoints.

**Shared limits:** `/auth/login` and `/auth/mobile/login` are on the same Gateway route, so they share
one per-IP rate-limit bucket — the split gives an attacker no extra budget.

**Mobile client guidance:**
- Store the refresh token in iOS Keychain / Android Keystore (EncryptedSharedPreferences), never plain
  preferences; keep the access token in memory.
- After every refresh, atomically replace the stored refresh token. Presenting the old one revokes the
  session.
- Serialize refreshes in the app (one in flight at a time) — two parallel refreshes of the same token
  trip reuse detection (gap #4).
- `refreshExpiresIn` in the response tells the app when the session ends without a refresh.

---

## 4. Controls in place

### Credentials
- **Argon2id** password hashing (`CryptoConfiguration`), with transparent re-hash on login when
  parameters are upgraded (`upgradeEncoding`).
- **No user enumeration on login.** Unknown email still runs one Argon2 verification against a dummy
  hash; wrong email and wrong password return the identical `401 "Invalid email or password"`.
- Emails normalized (trim + lower-case) before lookup and storage.
- `LoginRequest.toString()` / `RefreshRequest.toString()` mask secrets so they never reach logs.

### Brute force
- **Per-account lockout:** 5 failures within 15 min → locked 15 min (`ecom.auth.lockout.*`). The
  `login_failures` row is locked `FOR UPDATE`, so parallel guesses cannot race the counter.
- While locked, the password is **not** checked — the lock cannot be used as a correctness oracle.
- Failure paths commit despite the 401 (`@Transactional(noRollbackFor = EcomException.class)`).
- **Gateway per-IP rate limit** on `/auth/login`: ~10 attempts/min, burst 10. `TRUSTED_PROXY_HOPS=1`
  in k8s so the real client IP (not the ALB) is the key.

### Tokens
- Access token: **RS256 JWT, 15 min**. Gateway validates `iss`, `aud`, `exp`, `sub`; algorithm pinned to
  RS256; unknown `kid` triggers a JWKS refetch.
- Refresh token: 256-bit opaque random, **stored hashed**, **rotated on every use**, reuse → family
  revoked.
- Refresh cookie: `HttpOnly; Secure; SameSite=Strict; Path=/auth`. Browser endpoints never put the
  refresh token in JSON, so XSS cannot read it. Native clients use `/auth/mobile/*` (§3a).
- `Cache-Control: no-store` on every token-bearing response.
- Fails fast on startup without a signing key; ephemeral key mode is `false` in k8s.

### Logout
- Revokes the entire family and expires the cookie.
- Idempotent: unknown or blank token → `204`, no information leaked.
- Possession of the refresh token is the authority; no access token required.

### Network trust boundary
- user-service trusts `X-User-Id` blindly. This is safe only because:
  - the Gateway's `HeaderSanitizingFilter` strips every client-supplied `X-User-*` header, and
  - the NetworkPolicy `services-http-ingress-from-gateway-only` allows HTTP ingress only from the Gateway.
- CSRF protection is disabled at the Gateway. Acceptable because the refresh cookie is
  `SameSite=Strict` and JSON bodies force a CORS preflight against an origin allow-list.

---

## 5. Known gaps (ranked)

| # | Severity | Gap | Recommendation |
|---|---|---|---|
| 1 | Medium | **Lockout DoS.** Anyone knowing an email can lock that account indefinitely with 5 bad guesses every 15 min; per-IP limiting does not stop a botnet. | Key lockout per account+IP, or replace hard lock with progressive backoff / CAPTCHA after N failures; notify the user. |
| 2 | Medium | **No absolute session lifetime.** Each refresh mints `now + 14d` (`TokenIssuer.java:38`), so a used family — including an unnoticed stolen one — lives forever. | Store an absolute family expiry (e.g. login + 30–90 d) and carry it into every rotated token; refuse refresh past it. |
| 3 | Low | **Access token valid after logout.** JWT keeps working up to 15 min after logout or family revocation. | Standard stateless trade-off. If unacceptable: shorten TTL to ~5 min or add a short-lived deny-list at the Gateway. |
| 4 | Low (UX) | **Concurrent refresh logs user out.** Two tabs refreshing the same token → second sees ROTATED → family revoked. | Grace window (~10–30 s) in which a just-rotated token returns its successor instead of triggering revocation. |
| 5 | Low | **Timing enumeration of locked accounts.** Locked branch returns without Argon2; unknown-email branch pays one Argon2 → response time reveals "exists and is locked". | Call `passwordEncoder.matches(password, dummyHash)` on the locked branch too. |
| 6 | Low | **Registration enumerates emails.** `POST /users` returns `409 "An account with this email already exists"` and only has the default rate limit. | Strict per-IP limit on `/users`; with email verification, always return `202` and send an "account exists" email instead. |
| 7 | Info | **Refresh ignores account state.** Only user existence is checked. | When disable/ban or password change is added, revoke all of the user's families. |
| 8 | Info | **Argon2 parameters** are 16 MiB / t=2, slightly below OWASP's minimum (19 MiB / t=2). | `new Argon2PasswordEncoder(16, 32, 1, 19456, 2)`; existing hashes upgrade on next login. |
| 9 | Info | **Local Compose** sets `JWT_EPHEMERAL_KEY=true`. | Fine locally; keep it out of any shared environment (k8s base is correct). |
| 10 | Info | **Mobile sessions have the same lifetime as browser sessions.** | When adding the absolute family expiry (#2), consider a longer cap for native clients (record client type on the family). Optional hardening: DPoP (RFC 9449) or App Attest / Play Integrity to bind tokens to the device. |

Suggested first fixes: **#2** and **#5** — both small and contained. #1 and #4 need a product decision.

---

## 6. Configuration reference

| Property | Env var | Default | Notes |
|---|---|---|---|
| `ecom.jwt.access-token-ttl` | — | `15m` | Access JWT lifetime |
| `ecom.auth.refresh-token-ttl` | — | `14d` | Sliding; see gap #2 |
| `ecom.auth.refresh-cookie-name` | — | `refresh_token` | |
| `ecom.auth.refresh-cookie-path` | — | `/auth` | |
| `ecom.auth.lockout.max-failures` | — | `5` | |
| `ecom.auth.lockout.window` | — | `15m` | |
| `ecom.auth.lockout.duration` | — | `15m` | |
| `ecom.auth.ephemeral-jwt-key` | `JWT_EPHEMERAL_KEY` | `false` | Local only |
| `ecom.auth.cleanup-interval` | — | `PT1H` | Refresh-token purge cadence |
| `ecom.gateway.trusted-proxy-hops` (Gateway) | `TRUSTED_PROXY_HOPS` | `0` | `1` in k8s (behind ALB) |
