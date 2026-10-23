# Security release: deployment runbook

This release fixes critical authorization and data-exposure vulnerabilities and adds
Flyway migrations (V1 baseline, V2–V4). Every step marked **[approval]** changes production
and requires explicit approval before it is run. The backend stays closed to the public
(suspended, then maintenance mode) until it has been verified.

**Never redeploy a backend commit older than this release.** Older builds are vulnerable.
"Rollback" means maintenance mode, suspension, or a fix-forward (section 7), never the old
build.

## 1. Preflight (read-only)

Run `docs/preflight.sql` against production (Neon SQL editor, read-only session) and review:

| Check | Expected | If not |
|---|---|---|
| 1. Tables | `comments`, `posts`, `users`; no `flyway_schema_history` | Stop: schema differs from the assumed baseline |
| 2. Columns | Match `V1__baseline_schema.sql` (extra columns are fine) | Stop if a mapped column or table is missing |
| 3. New column names | No rows | Review conflicting definitions |
| 4. New constraint/index names | No rows | Rename in a new migration |
| 5. Case-insensitive duplicates | No rows | V2 would fail; resolving them changes data **[approval]** |
| 6. `deleted-user-` usernames | No rows | Rename the account first **[approval]** |
| 7. Row counts / PG version | PG ≥ 11 | Pre-11 would rewrite tables on ADD COLUMN DEFAULT |
| 8–9. Incident queries | Ideally no rows | Investigate (section 8) |
| 10. Demo account | Exists, verified | The seeder recreates it on boot if `app.seed-demo=true` |

Also confirm in Render (values not shared): `JWT_SECRET` is set and at least 32 bytes. The app
now refuses to start otherwise.

## 2. Migration compatibility and rollback assessment

| Migration | Change | Lock/duration on a small table | Reversibility |
|---|---|---|---|
| V1 | Not executed on production: recorded as baseline | — | — |
| V2 | `role`, `token_version` (constant defaults); role CHECK; unique indexes on `lower(user_name)`, `lower(email)` | ADD COLUMN with a constant default is metadata-only on PG ≥ 11. The CHECK validates existing rows and the index builds take brief write locks; milliseconds at this size | Additive |
| V3 | `password_reset_tokens` table | New table | Additive |
| V4 | `status` (default ACTIVE) + CHECK, `deleted_at`, `pending_email` | As V2 | Additive |

- Each migration runs in its own transaction (PostgreSQL DDL is transactional), so a failure
  leaves no partial change. The failure path for V2 is covered by `MigrationIT`.
- Existing rows become `role=USER`, `token_version=0`, `status=ACTIVE`. No existing data is
  modified (`MigrationIT` checks this on a populated database).
- Migrations are forward-only. There are no down-migrations; the database rollback path is the
  Neon backup branch (section 3). The extra columns are harmless to any future code.

## 3. Database backup and restore

**Backup** **[approval]**, immediately before deploying:
1. Neon console → project → **Branches** → **New branch**, parent `main` (or the production
   branch), from **current point in time**, named `pre-security-release-YYYYMMDD`.
2. On the new branch, run `docs/preflight.sql` query 7 and confirm the counts match production.

**Restore** (only if production data is damaged) **[approval]**:
- Put the backend in maintenance mode or suspend it first.
- Option A: Neon **Restore** the production branch to the timestamp just before deployment
  (point-in-time restore, within your plan's history window).
- Option B: point `DATABASE_URL` at the backup branch.
- Any data written after the backup is lost. Under this plan only smoke-test data is written
  before public access opens.

**Retention.** The backup branch contains personal data, including accounts later deleted
through the app. Delete it once the release is verified (suggested: within 14 days).

## 4. Deployment sequence (public access restricted throughout)

| # | Step | Approval |
|---|---|---|
| 0 | Backend is **suspended** in Render (done). | — |
| 1 | Review the preflight results together. | — |
| 2 | Create the Neon backup branch (section 3). | **[approval]** |
| 3 | In Render (still suspended), set environment variables: `MAINTENANCE_MODE=true`; `MAINTENANCE_BYPASS_KEY=<openssl rand -hex 32>`; rotate `JWT_SECRET` (section 6); remove `DDL_AUTO` (no longer read); keep `LOG_LEVEL` unset (INFO). | **[approval]** |
| 4 | Merge the backend PR. | **[approval]** |
| 5 | Resume the Render service. It builds the merged commit, Flyway applies V2–V4, and the app starts in maintenance mode: the public gets 503, `/health` returns ok. Confirm `Successfully applied 3 migrations` and no errors in the log. | **[approval]** |
| 6 | Promote the owner's account (after verifying ownership): `UPDATE users SET role = 'ADMIN' WHERE user_id = <id> AND user_name = '<username>';` (expect `UPDATE 1`). Never promote `demo`. | **[approval]** |
| 7 | Private smoke tests with the bypass header (section 5). | — |
| 8 | Merge the frontend PR (Vercel deploys). | **[approval]** |
| 9 | Set `MAINTENANCE_MODE=false` and remove `MAINTENANCE_BYPASS_KEY` (Render redeploys/restarts). | **[approval]** |
| 10 | Public smoke tests through the UI (section 5). | — |
| 11 | Re-enable the UserManager demo on the portfolio and profile README. | **[approval]** |

## 5. Smoke-test checklist

API base: `https://<render-host>/api`. During maintenance, add `-H "X-Maintenance-Key: $KEY"`.

**Private (step 7)**
- [ ] `GET /health` → 200 `ok` (without the key).
- [ ] `POST /user/login` without the key → 503.
- [ ] Demo login with the key → 200, `Authorization` header present, body has `"role":"USER"` and no `password`.
- [ ] Demo token: `GET /user/` → 403; `POST /user/update {"firstName":"x"}` → 403; `GET /posts/` → 200 with no emails or hashes in the body.
- [ ] Owner (ADMIN) token: `GET /user/` → 200, includes `status`; `POST /user/{demoId}/deactivate` is **not** run.
- [ ] Signup with `"userId":<existing id>,"role":"ADMIN"` → creates a new USER; the existing account is unchanged.
- [ ] A token issued before the release → 401.

**Public (step 10, through the UI)**
- [ ] Demo login works; no Admin link; profile edits show "can't be changed".
- [ ] New signup → verification email → verify → login.
- [ ] Password reset → works once → the same link again is rejected.
- [ ] Change email → current address unchanged → confirm from the new inbox → updated.
- [ ] Create post with image, comment, like/unlike, delete own post; another user's post can't be deleted.
- [ ] Owner can deactivate and reactivate a test account; the deactivated account can't log in.
- [ ] Render log shows no tokens, passwords, or request bodies.

## 6. Credential rotation

**JWT_SECRET** (step 3, and on any suspicion of exposure) **[approval]**
1. Generate: `openssl rand -base64 64` (≥ 32 bytes required, 64 recommended). Never commit it or paste it into chat or tickets.
2. Render → service → Environment → update `JWT_SECRET` → save (triggers a redeploy).
3. Effect: every existing session is signed out, and verification links already emailed stop working (users can request new ones). Password-reset links are unaffected (database tokens).
4. Verify: a token captured before rotation → 401; a fresh login works.
5. Store the new value only in Render and your password manager.

Note: tokens issued by the old build are already rejected by this release (they lack the
purpose claim), so rotation is defense in depth against an exposed secret, not the only
protection against the logged tokens.

**Other credentials.** Rotate `DATABASE_PASSWORD` (Neon role reset) or `RESEND_API_KEY` only if
there is evidence they were exposed; this release never logged them. Generate a new
`MAINTENANCE_BYPASS_KEY` for each maintenance window and remove it afterwards.

## 7. Rollback without reopening the vulnerable backend

| Situation | Action |
|---|---|
| Release misbehaves after opening | Set `MAINTENANCE_MODE=true` (immediate safe state), or suspend the service. Diagnose, then **fix forward** through a new PR (CI must pass). |
| Migration fails on startup | Nothing was partially applied. Keep the service suspended or in maintenance, fix the cause (usually preflight checks 5–6), redeploy the same release. |
| Data damaged | Maintenance mode, then restore from the Neon backup (section 3) **[approval]**. |
| Frontend issue | Redeploy the previous Vercel deployment (the old frontend is not the vulnerable component), or fix forward. |

Do not use Render's "Deploy a specific commit" with any commit earlier than the merge of the
security PR.

## 8. Incident assessment (production logs and data)

Before this release, production logged at DEBUG:
- the JWT filter logged full `Authorization` headers (access tokens, valid 24h);
- Spring MVC logged request bodies (truncated to about 100 characters):
  **`POST /user/reset` bodies, which start with the new password in plain text** (followed by
  part of the reset token). Login/signup/update bodies were logged as `User` objects, whose
  `toString()` excluded the password.

Also, any logged-in user could read every account's bcrypt hash, email and phone, and delete
users and posts.

Assessment steps:
1. Render → Logs: note the retention period available on the plan. Search for `/user/reset`,
   `Read "application/json`, `Auth Header`, `deleteUserById`, `DELETE /api/user`,
   `DELETE /api/posts`, `signup()`.
2. List the accounts whose reset bodies appear. Those passwords are exposed in plain text;
   ask the owners to change them (and anywhere they reused them).
3. Run preflight queries 8–9 and compare with expected activity.
4. If Render allows it, delete the affected logs; otherwise record that they expire with
   the plan's retention window. Check any log streams or drains for copies.
5. Treat all bcrypt hashes as exposed: owners of real accounts should change passwords.
6. Record findings and dates in an incident note.

## 9. Remaining risks after deployment

| Risk | Severity | Mitigation / next step |
|---|---|---|
| Plain-text reset passwords and access tokens in historical Render logs | **High** until assessed | Section 8; tokens are already invalid after deploy |
| Previously exposed bcrypt hashes (offline cracking of weak passwords) | **Medium** | Password changes for real accounts |
| Access tokens in localStorage, 24h lifetime (XSS would expose them) | **Medium** | `docs/security-review.md`: shorter lifetime now, cookie-based sessions as a separate proposal |
| In-memory rate limiting (single instance; if the proxy IP isn't resolved, per-IP limits become global) | **Medium** | Per-account limits don't depend on IP; verify client IPs in logs after deploy; shared store if scaled out |
| Demo visitors can post content visible to all users until the next restart | **Low** | Content rate limits; demo content cleared on every boot; admin can deactivate the demo account |
| Likes are not tracked per user (counts can be inflated) | **Low** | Per-user like table in a later release |
| Signup reveals whether a username or email is taken | **Low** | Accepted UX trade-off; rate-limited |
| Backend container runs as root | **Low** | Add a non-root `USER` to the Dockerfile |
| Neon backup branch retains personal data | **Low** | Delete after verification (section 3) |
