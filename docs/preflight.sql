-- Production preflight for the security release. READ-ONLY: every statement is a SELECT.
-- Run in the Neon SQL editor (or psql) against the production database and share the output.
-- Optionally enforce read-only first:  SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY;

-- 1. Tables present. Expected: comments, posts, users (and NO flyway_schema_history).
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;

-- 2. Column definitions, to compare with db/migration/V1__baseline_schema.sql.
SELECT table_name, column_name, data_type, character_maximum_length, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name IN ('users', 'posts', 'comments')
ORDER BY table_name, ordinal_position;

-- 3. Columns this release adds must not already exist (V2/V4 use IF NOT EXISTS, but a
--    conflicting definition would need review). Expected: no rows.
SELECT column_name
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'users'
  AND column_name IN ('role', 'token_version', 'status', 'deleted_at', 'pending_email');

-- 4. Constraint/index names this release creates must be unused. Expected: no rows.
SELECT conname AS name FROM pg_constraint
WHERE conname IN ('ck_users_role', 'ck_users_status', 'ux_password_reset_tokens_hash',
                  'fk_password_reset_tokens_user')
UNION ALL
SELECT indexname FROM pg_indexes
WHERE indexname IN ('ux_users_user_name_lower', 'ux_users_email_lower', 'ix_password_reset_tokens_user');

-- 5. Case-insensitive duplicates block the V2 unique indexes. Expected: no rows.
SELECT 'username' AS field, lower(user_name) AS value, count(*) AS n
FROM users GROUP BY lower(user_name) HAVING count(*) > 1
UNION ALL
SELECT 'email', lower(email), count(*)
FROM users WHERE email IS NOT NULL GROUP BY lower(email) HAVING count(*) > 1;

-- 6. Usernames in the newly reserved "deleted-user-" namespace. Expected: no rows.
SELECT user_id, user_name FROM users WHERE lower(user_name) LIKE 'deleted-user-%';

-- 7. Size of the data being migrated (V2/V4 add defaulted columns; this is instant on PG 11+).
SELECT (SELECT count(*) FROM users) AS users,
       (SELECT count(*) FROM posts) AS posts,
       (SELECT count(*) FROM comments) AS comments,
       version() AS postgres_version;

-- 8. Incident assessment: accounts overwritten through the old signup bug keep their
--    original (lower) user_id but get a new created_at, so created_at decreases as
--    user_id increases. Rows flagged here deserve a look.
SELECT user_id, user_name, created_at, prev_created_at
FROM (
  SELECT user_id, user_name, created_at,
         lag(created_at) OVER (ORDER BY user_id) AS prev_created_at
  FROM users
) t
WHERE prev_created_at IS NOT NULL AND created_at < prev_created_at
ORDER BY user_id;

-- 9. Incident assessment: gaps in id sequences (deleted rows or failed inserts).
SELECT 'users' AS tbl, s AS missing_id
FROM generate_series(1, (SELECT coalesce(max(user_id), 0) FROM users)) s
WHERE NOT EXISTS (SELECT 1 FROM users WHERE user_id = s)
UNION ALL
SELECT 'posts', s
FROM generate_series(1, (SELECT coalesce(max(id), 0) FROM posts)) s
WHERE NOT EXISTS (SELECT 1 FROM posts WHERE id = s)
ORDER BY 1, 2;

-- 10. Demo account state (must exist, verified). Shows no credentials.
SELECT user_id, user_name, email_verified, created_at, updated_at
FROM users WHERE user_name = 'demo';

-- =====================================================================================
-- Section B (added later): privileges and connection checks for Flyway. Still READ-ONLY.
-- Run these while connected AS THE SAME ROLE the application uses (DATABASE_USERNAME).
-- In the Neon SQL editor, pick that role in the role selector if it differs from the default.
-- =====================================================================================

-- B1. Which role is this session using? Must equal the app's DATABASE_USERNAME.
SELECT current_user, session_user, current_database();

-- B2. Owners of the existing tables. ALTER TABLE (V2, V4) and CREATE INDEX require the app
--     role to own "users". Expected: tableowner = the app role for all three tables.
SELECT tablename, tableowner FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;

-- B3. The app role can create tables in "public" (V3 creates password_reset_tokens, and
--     Flyway creates flyway_schema_history). Expected: true, true.
SELECT has_schema_privilege(current_user, 'public', 'CREATE') AS can_create_in_public,
       has_schema_privilege(current_user, 'public', 'USAGE')  AS can_use_public;

-- B4. Server settings relevant to running DDL (informational).
SELECT name, setting FROM pg_settings
WHERE name IN ('server_version', 'lock_timeout', 'statement_timeout', 'idle_in_transaction_session_timeout');

-- B5. NOT A QUERY: check whether DATABASE_URL in Render points at Neon's pooler
--     (host contains "-pooler"). If it does, set SPRING_FLYWAY_URL (and SPRING_FLYWAY_USER /
--     SPRING_FLYWAY_PASSWORD) to the DIRECT (non-pooler) endpoint so migrations run on a
--     normal session. The application itself can keep using the pooler.
