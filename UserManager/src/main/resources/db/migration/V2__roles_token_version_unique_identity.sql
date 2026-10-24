-- Authorization and session-invalidation support. Safe for a populated table:
-- every new column has a default, so existing rows become role USER with
-- token_version 0. No existing data is modified.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS role varchar(20) NOT NULL DEFAULT 'USER';

ALTER TABLE users
    ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));

-- Incremented whenever existing sessions must stop working (e.g. password reset).
-- Access tokens carry the version they were issued with and are rejected once it changes.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS token_version integer NOT NULL DEFAULT 0;

-- Case-insensitive uniqueness, enforced by the database rather than only by the
-- application, so concurrent signups cannot create duplicates.
-- PRE-DEPLOY CHECK: this fails if duplicates already exist. See docs/deployment.md
-- for the read-only query to run first.
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_user_name_lower ON users (lower(user_name));
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_email_lower ON users (lower(email));
