# Security design notes

## Authorization model

- **Roles:** `USER` (default) and `ADMIN`, stored in `users.role` and re-read from the database
  on every request. The `role` claim in the JWT is used only by the frontend to decide what to
  display.
- **Admin-only:** listing, looking up, deactivating, reactivating and deleting other accounts.
  Enforced by URL rules in `WebSecurityConfig` and by `@PreAuthorize` on the controller methods.
- **Ownership:** posts can be deleted by their author or an admin (service layer). Authors of
  posts and comments always come from the authenticated user, never from the request.
- **DTOs:** requests bind to records that only contain client-settable fields; responses never
  include password hashes or token versions, and post/comment authors expose only id,
  username and names.

## Tokens

| Token | Format | Lifetime | Accepted by | Single use |
|---|---|---|---|---|
| Access | HS512 JWT, `pur=access`, `uid`, `ver`, `role` | `JWT_EXPIRATION` (default 24h) | Every authenticated endpoint | No; revoked by `token_version` bump or account status |
| Email verification | HS512 JWT, `pur=verify-email`, bound to the target address | 24h | `GET /user/verify/email` only | Idempotent |
| Password reset | 256-bit random, only SHA-256 stored | 10 min | `POST /user/reset` only | Yes: conditional UPDATE, safe under concurrency |

Sessions are revoked (token_version incremented) on password reset, password change,
deactivation and deletion. Disabled and deleted accounts are rejected at login and by the
filter.

## Access-token lifetime and storage (review)

**Current state.** The access token lives for 24 hours in `localStorage` and is sent as a
Bearer header. The server re-checks the account (existence, status, verification, token
version) on every request, so password changes and admin actions take effect immediately.
A stolen token stays usable until it expires unless one of those events happens.

**Risks.** Any XSS in the frontend can read `localStorage`. A 24-hour lifetime gives a stolen
token a long window.

**Recommended now (configuration only, no redesign).**
1. Set `JWT_EXPIRATION` to 2–4 hours. Users re-authenticate more often; the frontend
   already sends expired sessions back to login on 401.
2. Add a Content-Security-Policy to the frontend's `vercel.json` to reduce XSS risk.

**Separate proposal (not implemented).** Move to an `HttpOnly; Secure; SameSite` cookie
session: a short access token plus a rotating refresh token stored server-side, with CSRF
protection (custom header or double-submit token), since the frontend (Vercel) and API (Render)
are on different sites. This removes token access from JavaScript entirely, but it changes CORS,
CSRF and the frontend's authentication code, so it needs its own design review.

## Rate limiting

In-memory fixed windows (`RateLimiter`), returning 429 with `Retry-After`:

| Action | Limit |
|---|---|
| Login | 30 / 15 min per IP; 10 / 15 min per username |
| Signup | 5 / hour per IP |
| Password-reset email | 10 / hour per IP; 3 / hour per address |
| Password-reset submit | 10 / hour per IP |
| Email verification | 20 / hour per IP |
| Current-password checks (password/email change, self-delete) | 10 / 15 min per user |
| Posts / comments / likes | 20 / 60 / 300 per hour per user |

Client IPs come from `X-Forwarded-For` through Tomcat's RemoteIpValve
(`server.forward-headers-strategy=native`), which only trusts internal proxy addresses. The
limiter is per instance; scaling out requires a shared store.

## Account lifecycle and personal data

| Operation | Who | Effect |
|---|---|---|
| Deactivate | Admin (not self, not the last active admin) | `status=DISABLED`; sessions revoked; reset links retired; content and attribution unchanged; reversible |
| Reactivate | Admin | `status=ACTIVE` (sessions from before deactivation stay revoked) |
| Delete | Admin (others), or the user with their current password | Irreversible anonymization: username → `deleted-user-<id>`; email, pending email, names, phone, country code cleared; password replaced with an unusable hash; role USER; `status=DELETED`; sessions revoked; reset tokens deleted; post images removed; post and comment text kept, attributed to the anonymized account. Not allowed for the last active admin or the demo account |

Where personal data remains after deletion, and for how long:

| Location | Remains | Until |
|---|---|---|
| Post and comment text | Whatever the user wrote | Removed by the author before deletion, or by an admin |
| Neon point-in-time history | Full pre-deletion rows | End of the plan's history window |
| Neon backup branches | Full copies | Branch deletion (see deployment runbook) |
| Render logs | Usernames/emails in log lines | Log retention window |
| Resend (email provider) | Recipient addresses in delivery logs | Provider retention policy |

## Demo account

- A plain USER; it can never be promoted in practice, because the seeder resets it to USER on
  every boot.
- Its profile and credentials can't be changed; it never receives reset links; it can't be
  deleted (an admin can deactivate it if it is being abused).
- Everything it posted is removed on each boot. Content creation is rate-limited.
- Seeded filler accounts get random passwords, so they can't be used with the public demo
  password.

## Logging

Default level INFO (`LOG_LEVEL`, `WEB_LOG_LEVEL`). Request records that carry secrets override
`toString()`, because Spring MVC logs parsed request bodies at DEBUG. `SensitiveLoggingIT`
asserts that no token or password appears in the logs even at DEBUG.

## Maintenance mode

`MAINTENANCE_MODE=true` returns 503 for everything except `/health` unless the request carries
`X-Maintenance-Key: <MAINTENANCE_BYPASS_KEY>`. It is used to deploy and smoke-test with public
access closed, and as the safe rollback state.
