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

## Access-token lifetime and storage

**Current state.** The access token lives for 24 hours in `localStorage` and is sent as a
Bearer header. The server re-checks the account (existence, status, verification, token
version) on every request, so password changes and admin actions take effect immediately.
A stolen token stays usable until it expires unless one of those events happens.

**Risks.** Any XSS in the frontend can read `localStorage`. A 24-hour lifetime gives a stolen
token a long window.

**Mitigations in place.**
- The frontend handles any 401 globally (an axios interceptor clears the token and returns to
  login), so a shorter lifetime never leaves a page broken.
- An enforced Content-Security-Policy plus `X-Content-Type-Options`, `Referrer-Policy`,
  `X-Frame-Options` and `Permissions-Policy` headers in `vercel.json`. Verified with a
  production-API build served with these exact headers: every route, logged in and out,
  rendered with zero CSP violations, and a positive control (injected inline script and
  external image) was blocked and reported.

**Recommended configuration (no code change):** `JWT_EXPIRATION=14400000` (4h).

**Remaining, separate risk: token theft from localStorage.** Even with the CSP and a 4-hour
lifetime, any script that runs on the frontend's origin can read the token. That includes an
XSS bug the CSP doesn't stop, a compromised npm dependency shipped in the bundle, or a malicious
browser extension. The token can then be used from anywhere until it expires, the password
changes, or the account is deactivated. The CSP reduces the chance and the shorter lifetime
reduces the window; neither removes the risk. Removing it requires the cookie-based session
design below.

**Separate proposal (not implemented).** Move to an `HttpOnly; Secure; SameSite` cookie
session: a short access token plus a rotating refresh token stored server-side, with CSRF
protection (custom header or double-submit token), since the frontend (Vercel) and API (Render)
are on different sites. This removes token access from JavaScript entirely, but it changes CORS,
CSRF and the frontend's authentication code, so it needs its own design review.

## Rate limiting

In-memory fixed windows (`RateLimiter`), returning 429 with `Retry-After`:

| Action | Limit |
|---|---|
| Login | 30 / 15 min per IP (when known); 10 / 15 min per username |
| Signup | 5 / hour per IP, or 50 / hour shared when the IP is unknown |
| Password-reset email | 10 / hour per IP, or 100 / hour shared when the IP is unknown; 3 / hour per address |
| Password-reset submit | 10 / hour per IP |
| Email verification | 20 / hour per IP |
| Current-password checks (password/email change, self-delete) | 10 / 15 min per user |
| Posts / comments / likes | 20 / 60 / 300 per hour per user |

Client IPs come from `ClientIpResolver`, never from `X-Forwarded-For` (Render appends to a
client-supplied value, so it can be forged). Modes: `UNKNOWN` (default: no header trusted;
per-IP rules fall back to global caps), `TRUSTED_HEADER` (one single-value header, honored only
when the TCP peer is inside `CLIENT_IP_TRUSTED_PROXIES`), `REMOTE_ADDR` (no proxy). The right
header is verified empirically during the maintenance window (runbook §8).

When the client IP is unknown, only email-sending endpoints (signup, reset request) fall
back to a shared global cap, to bound email cost. Login, verification and reset submission
have no shared cap, because one would let a single attacker lock out every visitor; they rely
on per-account limits and unguessable tokens instead.

Limitations: counters are in memory, so they reset on restart and aren't shared across
instances. This stops bursts, not slow distributed abuse.

## Account lifecycle and personal data

| Operation | Who | Effect |
|---|---|---|
| Deactivate | Admin (not self, not the last active admin) | `status=DISABLED`; sessions revoked; reset links retired; content and attribution unchanged; reversible |
| Reactivate | Admin | `status=ACTIVE` (sessions from before deactivation stay revoked) |
| Delete | Admin (others), or the user with their current password | Irreversible anonymization: internal username → `deleted-user-<id>`; email, pending email, names, phone, country code cleared; password replaced with an unusable hash; role USER; `status=DELETED`; sessions revoked; reset tokens deleted. The user's posts and comments stay in their threads (other people's replies are untouched), but their **text is replaced with `[deleted]` and images are removed**. Publicly the author appears as **"Deleted user"**, with no id or internal name. Not allowed for the last active admin or the demo account |

Where personal data remains after deletion, and for how long:

| Location | Remains | Until |
|---|---|---|
| The user's own post and comment text | Nothing: replaced with `[deleted]` at deletion | — |
| Other users' text that mentions the deleted user | Whatever they wrote | Can't be detected automatically; removed by an admin on request |
| Neon point-in-time history | Full pre-deletion rows | End of the plan's history window |
| Neon backup branches | Full copies | Branch deletion (see deployment runbook) |
| Render logs | Usernames/emails in log lines | Log retention window |
| Resend (email provider) | Recipient addresses in delivery logs | Provider retention policy |

## Demo account

- A plain USER; it can never be promoted in practice, because the seeder resets it to USER on
  every boot.
- Its profile and credentials can't be changed; it never receives reset links; it can't be
  deleted (an admin can deactivate it if it is being abused).
- Demo-authored posts and comments older than `DEMO_CONTENT_RETENTION` (default 24h) are
  disposable. `DemoContentCleanupService` removes them only when an admin calls
  `POST /api/admin/demo-content/cleanup`, or on `DEMO_CLEANUP_CRON` (off by default). A demo post
  that another user has commented on is kept, so no one else's data is removed. Content
  creation is rate-limited.
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
