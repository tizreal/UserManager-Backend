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

-- 9. Informational only: gaps in id sequences. PostgreSQL sequences skip values routinely
--    (rolled-back inserts, failed validations, crashes), so a gap is NOT evidence of deletion
--    on its own; it only matters if logs or audit records corroborate it.
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
-- Section B (added later): role, privilege and connection checks. Still READ-ONLY.
-- Two roles matter and may be the same or different:
--   * MIGRATION role: the one Flyway connects as (SPRING_FLYWAY_USER if set, otherwise
--     DATABASE_USERNAME). It must be able to run V2-V4.
--   * APPLICATION role: DATABASE_USERNAME. It must be able to read/write the tables afterwards.
-- Run B1-B3 connected as the MIGRATION role, and B1 + B6 connected as the APPLICATION role.
-- If they are the same role, run everything once. In the Neon SQL editor, choose the role in
-- the role selector.
-- =====================================================================================

-- B1. Which role is this session using?
SELECT current_user, session_user, current_database();

-- B2. (Migration role) Table owners. ALTER TABLE and CREATE INDEX on "users" (V2, V4) require
--     the migration role to own "users" (or be a member of the owning role).
SELECT t.tablename, t.tableowner,
       pg_has_role(current_user, t.tableowner, 'MEMBER') AS current_role_can_act_as_owner
FROM pg_tables t WHERE t.schemaname = 'public' ORDER BY t.tablename;

-- B3. (Migration role) Can create objects in "public" (V3 creates password_reset_tokens; Flyway
--     creates flyway_schema_history). Expected: true, true.
SELECT has_schema_privilege(current_user, 'public', 'CREATE') AS can_create_in_public,
       has_schema_privilege(current_user, 'public', 'USAGE')  AS can_use_public;

-- B4. Server settings relevant to running DDL (informational).
SELECT name, setting FROM pg_settings
WHERE name IN ('server_version', 'lock_timeout', 'statement_timeout', 'idle_in_transaction_session_timeout');

-- B5. NOT A QUERY: check whether DATABASE_URL in Render points at Neon's pooler
--     (host contains "-pooler"). If it does, set SPRING_FLYWAY_URL (and SPRING_FLYWAY_USER /
--     SPRING_FLYWAY_PASSWORD) to the DIRECT (non-pooler) endpoint so migrations run on a
--     normal session. The application itself can keep using the pooler.

-- B6. (Application role) Read/write access to the existing tables and their id sequences.
--     Expected: all true. (Owners have these implicitly.)
SELECT c.relname AS object, c.relkind,
       CASE WHEN c.relkind = 'r' THEN
            has_table_privilege(current_user, c.oid, 'SELECT')
        AND has_table_privilege(current_user, c.oid, 'INSERT')
        AND has_table_privilege(current_user, c.oid, 'UPDATE')
        AND has_table_privilege(current_user, c.oid, 'DELETE')
       ELSE has_sequence_privilege(current_user, c.oid, 'USAGE') END AS app_access_ok
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind IN ('r', 'S')
ORDER BY c.relkind, c.relname;

-- B7. (Either role) Only relevant when the two roles differ: default privileges the migration
--     role grants on tables/sequences it creates later (V3's password_reset_tokens). If no
--     row grants the application role access, V3's table will need an explicit GRANT.
SELECT pg_get_userbyid(d.defaclrole) AS creator_role, d.defaclobjtype AS object_type, d.defaclacl AS grants
FROM pg_default_acl d JOIN pg_namespace n ON n.oid = d.defaclnamespace
WHERE n.nspname = 'public';
