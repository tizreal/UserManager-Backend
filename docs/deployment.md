# Deploying the authorization and token-security changes

This release introduces Flyway migrations, roles, purpose-bound tokens, single-use password
reset, and stricter request handling. Follow the steps in order. Steps marked **[approval]**
change production and must be explicitly approved before they are run.

## 1. Before deploying (read-only)

Run these against the production database (Neon) with a read-only role or in a read-only
session. None of them modify data.

### 1.1 The schema matches the Flyway baseline (V1)

```sql
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public' ORDER BY table_name;
-- Expected: comments, posts, users (and no flyway_schema_history yet)

SELECT table_name, column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name IN ('users', 'posts', 'comments')
ORDER BY table_name, ordinal_position;
```

Compare the output with `UserManager/src/main/resources/db/migration/V1__baseline_schema.sql`.
Extra columns are harmless. A **missing** column, a missing table, or an existing
`flyway_schema_history` table means stop and review before deploying.

### 1.2 No case-insensitive duplicates (V2 adds unique indexes)

```sql
SELECT lower(user_name) AS username, count(*) FROM users GROUP BY 1 HAVING count(*) > 1;
SELECT lower(email)     AS email,    count(*) FROM users GROUP BY 1 HAVING count(*) > 1;
```

Both must return **no rows**. If they return rows, V2 fails and the new version won't start.
Nothing is partially applied, because PostgreSQL DDL is transactional (this is covered by
`MigrationIT`). Render normally keeps the previous deploy serving when a new one fails to
start; confirm that in the dashboard if it happens. Resolving duplicates means changing
production data **[approval]**.

### 1.3 Evidence of past abuse

```sql
-- Accounts overwritten through the old signup bug get a new created_at but keep their old
-- (lower) user_id, so created_at goes backwards as user_id increases.
SELECT user_id, user_name, email_verified, created_at FROM users ORDER BY user_id;

-- Gaps in the id sequences can indicate deleted accounts or posts (or failed inserts).
SELECT user_id FROM users ORDER BY user_id;
SELECT id FROM posts ORDER BY id;
```

Also search the Render logs (while they are retained) for `deleteUserById`, `DELETE /api/user/`,
`DELETE /api/posts/`, and unexpected `signup` activity.

## 2. Back up **[approval]**

Create a Neon branch (or point-in-time restore marker) of the production database immediately
before deploying. This is the data rollback path.

## 3. Environment variables (Render)

| Variable | Action |
|---|---|
| `JWT_SECRET` | Optional rotation **[approval]**. Tokens issued before this release are already rejected (they lack the new purpose claim), so rotation is hygiene rather than required. |
| `DDL_AUTO` | No longer read. Hibernate is fixed to `validate`; Flyway owns the schema. It can be removed. |
| `LOG_LEVEL`, `WEB_LOG_LEVEL` | Optional. Default `INFO`. |
| `DEMO_USERNAME` | Optional. Default `demo`. |
| `CORS_ALLOWED_ORIGINS` | Optional. Defaults to the previous origin list. |

## 4. Deploy, in this order

1. Keep the backend suspended (containment) until step 4.5.
2. Merge the backend pull request **[approval]**. On startup Flyway baselines the existing
   database at V1 and applies V2 and V3. Check the Render log for
   `Successfully applied 2 migrations`.
3. Promote the owner's account to ADMIN **[approval]**, after confirming the account belongs to
   you:
   ```sql
   UPDATE users SET role = 'ADMIN'
   WHERE user_id = <id> AND user_name = '<username>';   -- expect: UPDATE 1
   ```
   Never promote the demo account. The demo seeder resets it to USER on every boot anyway.
4. Merge the frontend pull request **[approval]** (Vercel deploys it).
5. Resume the backend in Render **[approval]**.
6. Smoke test (section 6). Only then re-enable the demo link on the portfolio **[approval]**.

## 5. Rollback

- **Application:** redeploy the previous commit in Render. The schema changes are additive and
  compatible with the previous code: new columns have defaults, so its inserts still work, and
  Hibernate `validate` ignores extra columns and tables. Do **not** drop the new columns or
  tables while rolling back.
- **Data:** restore from the Neon branch created in step 2 (only if data was damaged).
- **Frontend:** redeploy the previous Vercel deployment. The previous frontend works with the new
  backend except that admin-only screens return 403 for non-admins (which is intended).

## 6. Smoke test after deploying

- [ ] `GET /api/health` returns `ok`.
- [ ] Demo login works; the Admin link is hidden; `GET /api/user/` with the demo token returns 403.
- [ ] Demo profile edits return 403.
- [ ] Owner (ADMIN) can list users.
- [ ] Sign up a new account → verification email → verify → log in.
- [ ] Password reset email → reset once (works) → same link again (rejected).
- [ ] Create a post, comment, like, delete own post. Deleting someone else's post returns 403.
- [ ] Render log shows no tokens or passwords.

## 7. After the incident

- Old builds logged request bodies at DEBUG. **Password-reset requests were logged with the new
  password and token in plain text**, and access tokens were logged by the JWT filter. Delete or
  let expire the affected Render logs, and ask anyone who reset a password before this release to
  change it again.
- Password hashes were readable by any logged-in user through the old API. Owners of real
  accounts should change their passwords.
