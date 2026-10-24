-- =====================================================================================
-- Production preflight for the security release.
-- READ-ONLY: every statement is a SELECT. Nothing here modifies data, schema or privileges.
-- Optional, first, in the same session:  SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY;
--
-- Results are labelled:
--   BLOCKER  must be resolved before deploying (resolution may change data: needs approval)
--   REVIEW   needs a decision; may or may not block
--   INFO/OK  no action needed
-- If your SQL editor shows only some result sets, run the queries one at a time and keep the
-- numbers (S1..., D1..., I1..., B1...).
-- =====================================================================================


-- ===================== S: does production match the Flyway V1 baseline? ===============

-- S1. Tables. Expected: comments, posts, users present; flyway_schema_history and
--     password_reset_tokens absent (Flyway and V3 create them).
SELECT t.name,
       (to_regclass('public.' || t.name) IS NOT NULL) AS present,
       CASE
         WHEN t.expected AND to_regclass('public.' || t.name) IS NULL THEN 'BLOCKER: missing table'
         WHEN NOT t.expected AND to_regclass('public.' || t.name) IS NOT NULL THEN 'BLOCKER: must not exist yet'
         ELSE 'OK'
       END AS status
FROM (VALUES ('users', true), ('posts', true), ('comments', true),
             ('flyway_schema_history', false), ('password_reset_tokens', false)) AS t(name, expected)
ORDER BY t.expected DESC, t.name;

-- S2. Every column the application maps, compared with production.
--     Missing columns and type differences stop the app (Hibernate validates the schema at
--     startup). Nullability differences are reported for review only.
WITH expected(table_name, column_name, data_type, not_null) AS (VALUES
  ('users',    'user_id',        'integer',                     true),
  ('users',    'first_name',     'character varying',           false),
  ('users',    'last_name',      'character varying',           false),
  ('users',    'user_name',      'character varying',           false),
  ('users',    'password',       'character varying',           false),
  ('users',    'phone_number',   'character varying',           false),
  ('users',    'email',          'character varying',           false),
  ('users',    'email_verified', 'boolean',                     false),
  ('users',    'country_code',   'character varying',           false),
  ('users',    'created_at',     'timestamp without time zone', false),
  ('users',    'updated_at',     'timestamp without time zone', false),
  ('posts',    'id',             'bigint',                      true),
  ('posts',    'content',        'text',                        true),
  ('posts',    'user_id',        'integer',                     true),
  ('posts',    'likes_count',    'integer',                     false),
  ('posts',    'updated_at',     'timestamp without time zone', false),
  ('posts',    'created_at',     'timestamp without time zone', true),
  ('posts',    'image',          'text',                        false),
  ('comments', 'id',             'bigint',                      true),
  ('comments', 'content',        'text',                        true),
  ('comments', 'post_id',        'bigint',                      true),
  ('comments', 'user_id',        'integer',                     true),
  ('comments', 'created_at',     'timestamp without time zone', true),
  ('comments', 'likes_count',    'integer',                     false),
  ('comments', 'updated_at',     'timestamp without time zone', false)
),
actual AS (
  SELECT table_name, column_name, data_type, character_maximum_length, is_nullable = 'NO' AS not_null
  FROM information_schema.columns
  WHERE table_schema = 'public' AND table_name IN ('users', 'posts', 'comments')
)
SELECT e.table_name, e.column_name, e.data_type AS expected_type, a.data_type AS actual_type,
       a.character_maximum_length AS actual_length,
       CASE
         WHEN a.column_name IS NULL THEN 'BLOCKER: column missing'
         WHEN a.data_type <> e.data_type THEN 'BLOCKER: type differs (Hibernate validation may fail)'
         WHEN NOT e.not_null AND a.not_null THEN 'REVIEW: NOT NULL in production but optional in the app'
         WHEN e.not_null AND NOT a.not_null THEN 'INFO: nullable in production (the app always sets it)'
         ELSE 'OK'
       END AS status
FROM expected e
LEFT JOIN actual a USING (table_name, column_name)
ORDER BY e.table_name, e.column_name;

-- S3. Extra columns in production that the application does not map. INFO only: Hibernate
--     ignores them, unless one is NOT NULL without a default (inserts would then fail).
SELECT c.table_name, c.column_name, c.data_type, c.is_nullable, c.column_default,
       CASE WHEN c.is_nullable = 'NO' AND c.column_default IS NULL AND c.is_identity = 'NO'
            THEN 'BLOCKER: NOT NULL without default (app inserts would fail)'
            ELSE 'INFO: unmapped column' END AS status
FROM information_schema.columns c
WHERE c.table_schema = 'public' AND c.table_name IN ('users', 'posts', 'comments')
  AND (c.table_name, c.column_name) NOT IN (VALUES
    ('users','user_id'),('users','first_name'),('users','last_name'),('users','user_name'),
    ('users','password'),('users','phone_number'),('users','email'),('users','email_verified'),
    ('users','country_code'),('users','created_at'),('users','updated_at'),
    ('posts','id'),('posts','content'),('posts','user_id'),('posts','likes_count'),
    ('posts','updated_at'),('posts','created_at'),('posts','image'),
    ('comments','id'),('comments','content'),('comments','post_id'),('comments','user_id'),
    ('comments','created_at'),('comments','likes_count'),('comments','updated_at'))
ORDER BY c.table_name, c.column_name;

-- S4. Primary keys. Expected: users(user_id), posts(id), comments(id).
WITH expected(table_name, pk) AS (VALUES ('users', 'user_id'), ('posts', 'id'), ('comments', 'id')),
actual AS (
  SELECT cl.relname AS table_name,
         string_agg(a.attname, ',' ORDER BY k.ord) AS pk
  FROM pg_constraint con
  JOIN pg_class cl ON cl.oid = con.conrelid
  JOIN pg_namespace n ON n.oid = cl.relnamespace AND n.nspname = 'public'
  CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
  JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum
  WHERE con.contype = 'p'
  GROUP BY cl.relname
)
SELECT e.table_name, e.pk AS expected_pk, a.pk AS actual_pk,
       CASE WHEN a.pk IS NULL THEN 'BLOCKER: no primary key'
            WHEN a.pk <> e.pk THEN 'BLOCKER: different primary key'
            ELSE 'OK' END AS status
FROM expected e LEFT JOIN actual a USING (table_name)
ORDER BY e.table_name;

-- S5. Database-generated ids. The app inserts without ids (IDENTITY strategy), so each id
--     column must be an identity column or have a nextval() default.
SELECT c.table_name, c.column_name, c.is_identity, c.column_default,
       CASE WHEN c.is_identity = 'YES' OR c.column_default LIKE 'nextval(%' THEN 'OK'
            ELSE 'BLOCKER: id is not generated by the database' END AS status
FROM information_schema.columns c
WHERE c.table_schema = 'public'
  AND (c.table_name, c.column_name) IN (VALUES ('users', 'user_id'), ('posts', 'id'), ('comments', 'id'))
ORDER BY c.table_name;

-- S6. Foreign keys (relationships). Constraint names may differ; only the columns matter.
--     A missing foreign key does not stop the app, but then the database does not prevent
--     orphaned rows; see D1.
WITH expected(table_name, column_name, ref_table, ref_column) AS (VALUES
  ('posts',    'user_id', 'users', 'user_id'),
  ('comments', 'post_id', 'posts', 'id'),
  ('comments', 'user_id', 'users', 'user_id')
),
actual AS (
  SELECT src.relname AS table_name, sa.attname AS column_name,
         dst.relname AS ref_table, da.attname AS ref_column, con.conname
  FROM pg_constraint con
  JOIN pg_class src ON src.oid = con.conrelid
  JOIN pg_namespace n ON n.oid = src.relnamespace AND n.nspname = 'public'
  JOIN pg_class dst ON dst.oid = con.confrelid
  CROSS JOIN LATERAL unnest(con.conkey, con.confkey) AS k(src_att, dst_att)
  JOIN pg_attribute sa ON sa.attrelid = con.conrelid AND sa.attnum = k.src_att
  JOIN pg_attribute da ON da.attrelid = con.confrelid AND da.attnum = k.dst_att
  WHERE con.contype = 'f' AND array_length(con.conkey, 1) = 1
)
SELECT e.table_name, e.column_name, e.ref_table || '(' || e.ref_column || ')' AS references,
       a.conname AS constraint_name,
       CASE WHEN a.conname IS NULL THEN 'REVIEW: foreign key missing (check D1 for orphans)'
            ELSE 'OK' END AS status
FROM expected e
LEFT JOIN actual a USING (table_name, column_name, ref_table, ref_column)
ORDER BY e.table_name, e.column_name;

-- S7. Names this release creates must not already be in use. Expected: no rows.
SELECT 'column' AS kind, column_name AS name
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'users'
  AND column_name IN ('role', 'token_version', 'status', 'deleted_at', 'pending_email')
UNION ALL
SELECT 'constraint', conname FROM pg_constraint
WHERE connamespace = 'public'::regnamespace
  AND conname IN ('ck_users_role', 'ck_users_status', 'ux_password_reset_tokens_hash', 'fk_password_reset_tokens_user')
UNION ALL
SELECT 'index', indexname FROM pg_indexes
WHERE schemaname = 'public'
  AND indexname IN ('ux_users_user_name_lower', 'ux_users_email_lower', 'ix_password_reset_tokens_user');

-- S8. Database version and data size. V2/V4 add columns with constant defaults, which is a
--     metadata-only change on PostgreSQL 11+ (no table rewrite).
SELECT current_setting('server_version_num')::int >= 110000 AS pg11_or_newer,
       current_setting('server_version') AS server_version,
       (SELECT count(*) FROM users) AS users,
       (SELECT count(*) FROM posts) AS posts,
       (SELECT count(*) FROM comments) AS comments;


-- ===================== D: data that would break V2-V4 or the new code =================

-- D1. Orphaned rows. Every post/comment loads its author (and comments their post); an
--     orphan would make the feed fail after deployment. Expected: all zero.
SELECT 'posts without an existing author' AS check_name, count(*) AS n,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'BLOCKER' END AS status
FROM posts p WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.user_id = p.user_id)
UNION ALL
SELECT 'comments without an existing post', count(*),
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'BLOCKER' END
FROM comments c WHERE NOT EXISTS (SELECT 1 FROM posts p WHERE p.id = c.post_id)
UNION ALL
SELECT 'comments without an existing author', count(*),
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'BLOCKER' END
FROM comments c WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.user_id = c.user_id);

-- D2. Case-insensitive duplicates. V2's unique indexes on lower(user_name) / lower(email)
--     would fail. Expected: no rows. (Returns usernames/emails: mask real emails before sharing.)
SELECT 'username' AS field, lower(user_name) AS value, count(*) AS n, 'BLOCKER' AS status
FROM users WHERE user_name IS NOT NULL GROUP BY lower(user_name) HAVING count(*) > 1
UNION ALL
SELECT 'email', lower(email), count(*), 'BLOCKER'
FROM users WHERE email IS NOT NULL GROUP BY lower(email) HAVING count(*) > 1;

-- D3. Usernames in the newly reserved "deleted-user-" namespace. Expected: no rows.
SELECT user_id, user_name, 'REVIEW: rename before deploying' AS status
FROM users WHERE lower(user_name) LIKE 'deleted-user-%';

-- D4. Account fields the new code relies on. Accounts with these values cannot sign in after
--     the release (the new code requires email_verified = true; login needs a username and
--     password). REVIEW, not necessarily BLOCKER: they may be abandoned test accounts.
SELECT count(*) FILTER (WHERE email_verified IS NULL)  AS email_verified_null,
       count(*) FILTER (WHERE user_name IS NULL)       AS user_name_null,
       count(*) FILTER (WHERE email IS NULL)           AS email_null,
       count(*) FILTER (WHERE password IS NULL)        AS password_null
FROM users;


-- ===================== I: informational context (not proof) ===========================

-- I1. Timestamp anomaly: an account whose created_at is later than that of some account
--     with a HIGHER user_id (ids are assigned in creation order). This flags the lower-id
--     row and shows the earliest later-id timestamp it exceeds. NOT PROOF of anything:
--     manual edits, imports, restores, scripts or clock changes cause the same pattern. Corroborate with request logs before drawing conclusions. (The demo
--     seeder does not change created_at of existing accounts.)
SELECT user_id, user_name, created_at, updated_at, earliest_created_at_of_higher_ids
FROM (
  SELECT user_id, user_name, created_at, updated_at,
         min(created_at) OVER (ORDER BY user_id
                               ROWS BETWEEN 1 FOLLOWING AND UNBOUNDED FOLLOWING) AS earliest_created_at_of_higher_ids
  FROM users
) t
WHERE created_at > earliest_created_at_of_higher_ids
ORDER BY user_id;

-- I2. Id range summary. INFO only: sequences skip values routinely (rolled-back inserts,
--     failed validations, restarts), so missing ids are NOT evidence of deletion without
--     corroborating logs or audit records.
SELECT 'users' AS table_name, count(*) AS rows, min(user_id) AS min_id, max(user_id) AS max_id,
       coalesce(max(user_id) - min(user_id) + 1 - count(*), 0) AS ids_not_present
FROM users
UNION ALL
SELECT 'posts', count(*), min(id), max(id), coalesce(max(id) - min(id) + 1 - count(*), 0) FROM posts
UNION ALL
SELECT 'comments', count(*), min(id), max(id), coalesce(max(id) - min(id) + 1 - count(*), 0) FROM comments;

-- I3. Demo account. The seeder (if enabled) resets it on every boot, so updated_at close to
--     recent restarts suggests seeding is active. Shows no credentials.
SELECT user_id, user_name, email_verified, created_at, updated_at
FROM users WHERE user_name = 'demo';


-- ===================== B: roles, privileges and connection ===========================
-- Two roles matter and may be the same or different:
--   * MIGRATION role: what Flyway connects as (SPRING_FLYWAY_USER if set, else DATABASE_USERNAME).
--   * APPLICATION role: DATABASE_USERNAME.
-- Run B1-B3 connected as the MIGRATION role and B1 + B5 connected as the APPLICATION role.
-- If they are the same role, run everything once. (Neon SQL editor: use the role selector.)

-- B1. Which role is this session using?
SELECT current_user, session_user, current_database();

-- B2. (Migration role) ALTER TABLE / CREATE INDEX on "users" (V2, V4) require owning "users"
--     or being a member of the owning role. Expected: OK.
SELECT t.tablename, t.tableowner,
       CASE WHEN pg_has_role(current_user, t.tableowner, 'MEMBER') THEN 'OK'
            ELSE 'BLOCKER for the migration role: cannot alter this table' END AS status
FROM pg_tables t
WHERE t.schemaname = 'public' AND t.tablename IN ('users', 'posts', 'comments')
ORDER BY t.tablename;

-- B3. (Migration role) Can create objects in "public" (V3's password_reset_tokens and
--     Flyway's flyway_schema_history). Expected: true, true.
SELECT has_schema_privilege(current_user, 'public', 'CREATE') AS can_create_in_public,
       has_schema_privilege(current_user, 'public', 'USAGE')  AS can_use_public;

-- B4. NOT A QUERY. In Render, check whether DATABASE_URL's host contains "-pooler". If it
--     does, set SPRING_FLYWAY_URL (+ SPRING_FLYWAY_USER / SPRING_FLYWAY_PASSWORD) to the
--     DIRECT endpoint so migrations run on a normal session; the app can keep the pooler.

-- B5. (Application role) Access to the application's own tables and id sequences only.
--     Expected: all OK. (Owners have these implicitly.) For identity columns, INSERT alone
--     is enough; sequence USAGE matters for serial/nextval() columns.
SELECT 'table' AS kind, t.name AS object,
       CASE
         WHEN to_regclass('public.' || t.name) IS NULL THEN 'n/a: table missing (see S1)'
         WHEN has_table_privilege(current_user, to_regclass('public.' || t.name), 'SELECT')
          AND has_table_privilege(current_user, to_regclass('public.' || t.name), 'INSERT')
          AND has_table_privilege(current_user, to_regclass('public.' || t.name), 'UPDATE')
          AND has_table_privilege(current_user, to_regclass('public.' || t.name), 'DELETE') THEN 'OK'
         ELSE 'BLOCKER: application role lacks SELECT/INSERT/UPDATE/DELETE'
       END AS status
FROM (VALUES ('users'), ('posts'), ('comments')) AS t(name)
UNION ALL
SELECT 'sequence', coalesce(s.seq, s.tbl || '.' || s.col || ' (no sequence; see S5)'),
       CASE
         WHEN s.seq IS NULL THEN 'n/a'
         WHEN has_sequence_privilege(current_user, s.seq, 'USAGE') THEN 'OK'
         ELSE 'REVIEW: no USAGE (matters for serial/nextval ids)'
       END
FROM (
  SELECT v.tbl, v.col,
         CASE WHEN to_regclass('public.' || v.tbl) IS NOT NULL
              THEN pg_get_serial_sequence('public.' || v.tbl, v.col) END AS seq
  FROM (VALUES ('users', 'user_id'), ('posts', 'id'), ('comments', 'id')) AS v(tbl, col)
) s;

-- B6. Only if the migration and application roles DIFFER: default privileges.
--     Default privileges are tied to the role that CREATES an object: only rows whose
--     "objects_created_by" is the MIGRATION role apply to the table V3 creates
--     (password_reset_tokens) and its identity sequence. They are needed only if a row grants
--     the APPLICATION role (or PUBLIC) SELECT, INSERT, UPDATE, DELETE on tables (object_type
--     'r') and USAGE on sequences ('S'). If no such row exists, plan explicit grants after V3
--     (needs approval):
--       GRANT SELECT, INSERT, UPDATE, DELETE ON public.password_reset_tokens TO <app_role>;
--       GRANT USAGE ON SEQUENCE public.password_reset_tokens_id_seq TO <app_role>;
SELECT pg_get_userbyid(d.defaclrole) AS objects_created_by,
       coalesce(n.nspname, '(all schemas)') AS scope,
       d.defaclobjtype AS object_type,
       CASE WHEN x.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(x.grantee) END AS grantee,
       x.privilege_type
FROM pg_default_acl d
LEFT JOIN pg_namespace n ON n.oid = d.defaclnamespace
CROSS JOIN LATERAL aclexplode(d.defaclacl) AS x
WHERE d.defaclnamespace = 0 OR n.nspname = 'public'
ORDER BY 1, 2, 3, 4, 5;
