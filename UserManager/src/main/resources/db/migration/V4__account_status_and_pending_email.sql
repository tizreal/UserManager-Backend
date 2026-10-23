-- Account lifecycle and verified email changes. Additive and safe for a populated
-- table: existing rows become ACTIVE with no pending email.

-- ACTIVE: normal. DISABLED: deactivated by an admin (reversible); cannot log in.
-- DELETED: personal data anonymized (irreversible); cannot log in.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS status varchar(20) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE users
    ADD CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED'));

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS deleted_at timestamp(6) with time zone;

-- A requested new email address. It replaces users.email only after the user
-- confirms it from the new inbox; until then the current address stays in effect.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS pending_email varchar(254);
