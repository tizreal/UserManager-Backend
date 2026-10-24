# Security release: final deployment checklist

Tick in order. Details for every step are in `docs/deployment.md` (section numbers in brackets).

- **[A]** = stop: needs the owner's explicit approval before it is done.
- **Who:** **You** = manual, in a console only the owner can access (Neon, Render, Vercel,
  inbox). **Claude** = can be done from the repository/terminal once approved.
- Never resume the old Render service. Never deploy a commit older than the security merge.
- If anything looks wrong at any point: `MAINTENANCE_MODE=true` on the new service, then stop.

## Phase A — verification (no production change)

| # | Step | Who | Gate |
|---|---|---|---|
| A1 | Preflight on production reviewed (§1) | You + Claude | Done 2026-10-23: all pass |
| A2 | Backup branch exists (child of production) | You | Done 2026-10-23 |
| A3 | Fingerprint query on production and on the backup branch: identical, `flyway_history_absent = 1` (§5) | You (read-only SQL) | — |
| A4 | Create the rehearsal branch **from the backup branch**; reset `neondb_owner`'s password on the rehearsal branch only; env file outside the repo (§4) | You | — |
| A5 | Run `scripts/rehearsal/rehearse.sh <env-file>` on the PR branch: `64 passed, 0 failed` | You or Claude (Claude only reads the env file through the script, never prints it) | — |
| A6 | Optional restore drill on a disposable `restore-drill` branch (§5) | You | — |
| A7 | Review rehearsal output; delete the env file; delete rehearsal/drill branches | You | **[A]** delete branches |
| A8 | Backend PR and frontend PR: CI green; final diffs reviewed | You + Claude | — |
| A9 | Generate locally, store in a password manager: `JWT_SECRET` (`openssl rand -base64 64`), `MAINTENANCE_BYPASS_KEY` (`openssl rand -hex 32`). Identify the owner account to promote (not `demo`/`ada`/`alan`/`grace`) | You | — |
| A10 | **Deployment approval**: owner approves phases B–E | You | **[A]** |

## Phase B — code

| # | Step | Who | Gate |
|---|---|---|---|
| B1 | Old Render service: Settings → Auto-Deploy **Off**; confirm it is still **suspended** | You | **[A]** |
| B2 | Mark backend PR #1 ready and merge; note the merge SHA | Claude or you | **[A]** |

## Phase C — backend on the new service (closed to the public)

| # | Step | Who | Gate |
|---|---|---|---|
| C1 | Session check (§6): no other active/idle-in-transaction sessions; close the SQL editor | You (read-only SQL) | — |
| C2 | Create a new Render web service: repo `UserManager-Backend`, branch `master`, root `UserManager`, Docker, Auto-Deploy off, **all env vars from §3 in the creation form** (`MAINTENANCE_MODE=true` included) | You | **[A]** |
| C3 | First deploy: Events show the merge SHA; log shows `baselined ... version: 1`, `Successfully applied 3 migrations`, `now at version v4`; no errors | You (Claude can review a pasted log) | — |
| C4 | Flyway/data checks (read-only SQL, §2/§4): history `1 BASELINE, 2, 3, 4` all successful; 5 users, all `USER`/`0`/`ACTIVE`; `password_reset_tokens` empty | You | — |
| C5 | Maintenance checks: `/api/health` 200; login/feed 503 without key and with a wrong key | You or Claude (key stays in your terminal) | — |
| C6 | Client-IP verification (§8); decide `CLIENT_IP_MODE`/`HEADER` | You | — |
| C7 | Owner password change via API with the bypass key (§7) | You | — |
| C8 | Promote the owner: the §7 `UPDATE` → `UPDATE 1` | You | **[A]** |
| C9 | Private smoke tests (§12) | You | — |

## Phase D — frontend

| # | Step | Who | Gate |
|---|---|---|---|
| D1 | Commit to frontend PR #1: `vercel.json` `connect-src` → new host; CI green (§10) | Claude | **[A]** push |
| D2 | Vercel: `VITE_API_URL=https://<new-render-host>` (Production + Preview) | You | **[A]** |
| D3 | Mark frontend PR ready and merge; Vercel production deploy shows that commit | Claude or you | **[A]** |
| D4 | Hostname wiring checks (§10): CSP header and JS bundle contain only the new host; CORS allows the frontend origin | You or Claude | — |

## Phase E — opening

| # | Step | Who | Gate |
|---|---|---|---|
| E1 | New service env: `MAINTENANCE_MODE=false`, delete `MAINTENANCE_BYPASS_KEY`, `CLIENT_IP_DIAGNOSTICS=false`, final `CLIENT_IP_*`; confirm the redeploy shows the same SHA | You | **[A]** |
| E2 | Public smoke tests through the UI (§12), including CSP (no console violations) | You | — |
| E3 | Optional: Auto-Deploy on for the new service | You | **[A]** |

## Phase F — after

| # | Step | Who | Gate |
|---|---|---|---|
| F2 | Delete the old Render service | You | **[A]** |
| F3 | Re-enable the UserManager demo on the portfolio and profile README | Claude | **[A]** |
| F4 | Owners of real accounts asked to change passwords | You | — |
| F5 | Delete the Neon backup branch after the verification period (≈14 days) | You | **[A]** |

## If something goes wrong (§9)

- [ ] `MAINTENANCE_MODE=true` on the new service (or suspend it). Never touch the old service.
- [ ] Migration failure: the app doesn't start; check `flyway_schema_history` (may be at V1, V2
      or V3); fix the cause; redeploy the **new** service. Restore only if data is damaged **[A]**.
- [ ] Frontend: fix forward; roll back only to a deployment built after the frontend merge.
