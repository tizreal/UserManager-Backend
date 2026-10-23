# Security release: production checklist

Condensed from `docs/deployment.md`. Tick each box in order; **[A]** = needs explicit approval.

## Before the window
- [ ] Preflight (`docs/preflight.sql`, sections 1–10 and B) reviewed; all "expected" results met
- [ ] Backend PR and frontend PR: CI green; final diffs reviewed
- [ ] Owner account (to promote) identified and ownership verified
- [ ] `JWT_SECRET` and `MAINTENANCE_BYPASS_KEY` generated locally (not shared)

## Window
- [ ] Backend still suspended in Render
- [ ] **[A]** Neon backup branch created; counts match production
- [ ] **[A]** Render env set: `MAINTENANCE_MODE=true`, `MAINTENANCE_BYPASS_KEY`, new `JWT_SECRET`, `CLIENT_IP_MODE=UNKNOWN`, `CLIENT_IP_DIAGNOSTICS=true`, Flyway direct URL if pooled; `DDL_AUTO` removed
- [ ] **[A]** Backend PR merged
- [ ] **[A]** Service resumed; log shows `Successfully applied 3 migrations`; `/api/health` ok; other endpoints 503
- [ ] Client-IP verification done (runbook §6); chosen `CLIENT_IP_MODE`/`HEADER` noted
- [ ] **[A]** Owner promoted to ADMIN (`UPDATE 1`)
- [ ] Private smoke tests pass (runbook §10)
- [ ] **[A]** Frontend PR merged; Vercel deploy done
- [ ] **[A]** Final env: `CLIENT_IP_*` set, diagnostics off, `MAINTENANCE_MODE=false`, bypass key deleted
- [ ] Public smoke tests pass; no CSP violations; logs clean

## After
- [ ] **[A]** UserManager demo re-enabled on portfolio and profile README
- [ ] Incident assessment completed (runbook §9); affected users asked to change passwords
- [ ] Optional **[A]**: `JWT_EXPIRATION=14400000` (4h)
- [ ] Neon backup branch deleted after the verification period

## If anything goes wrong
- [ ] `MAINTENANCE_MODE=true` or suspend; never redeploy a pre-release commit; fix forward or restore (runbook §8)
