-- Local stand-in for production, used to dry-run rehearse.sh before the Neon run.
-- The pre-Flyway schema (identical to V1) plus five users shaped like production's:
-- the four accounts the demo seeder manages and one ordinary account. No posts/comments.
-- Load V1__baseline_schema.sql first, then this file (no Flyway history: like production).

INSERT INTO users (first_name, last_name, user_name, password, phone_number, email,
                   email_verified, country_code, created_at, updated_at) VALUES
  ('Demo',  'User',     'demo',  '$2a$10$abcdefghijklmnopqrstuuJ3h1q0E3v7b9nV1jB6YqzK0t7gZ1Q5e', '5550100000', 'demo@usermanager.dev',          true, '+1', '2026-01-10', '2026-01-10'),
  ('Ada',   'Lovelace', 'ada',   '$2a$10$abcdefghijklmnopqrstuuJ3h1q0E3v7b9nV1jB6YqzK0t7gZ1Q5e', '5550100000', 'ada.lovelace@usermanager.dev',  true, '+1', '2026-01-10', '2026-01-10'),
  ('Alan',  'Turing',   'alan',  '$2a$10$abcdefghijklmnopqrstuuJ3h1q0E3v7b9nV1jB6YqzK0t7gZ1Q5e', '5550100000', 'alan.turing@usermanager.dev',   true, '+1', '2026-01-10', '2026-01-10'),
  ('Grace', 'Hopper',   'grace', '$2a$10$abcdefghijklmnopqrstuuJ3h1q0E3v7b9nV1jB6YqzK0t7gZ1Q5e', '5550100000', 'grace.hopper@usermanager.dev',  true, '+1', '2026-01-10', '2026-01-10'),
  ('Owner', 'Account',  'owner', '$2a$10$abcdefghijklmnopqrstuuJ3h1q0E3v7b9nV1jB6YqzK0t7gZ1Q5e', '5550111111', 'owner@example.invalid',         true, '+1', '2026-02-01', '2026-02-01');
