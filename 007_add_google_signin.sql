-- migrations/007_add_google_signin.sql
-- Adds Google Sign-In support for Android patients. iOS patients
-- auto-provision via Apple Sign-In (apple_user_id); Android has no
-- equivalent native platform sign-in tied to this backend, so patients
-- on Android need their own path — Google Sign-In is the natural
-- equivalent, and reuses the exact same auto-provisioning pattern as
-- Apple's (see POST /auth/apple).

BEGIN;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS google_user_id TEXT UNIQUE;

CREATE INDEX IF NOT EXISTS idx_users_google_user_id ON users (google_user_id);

COMMIT;
