-- P1 authentication: token revocation and hashed-identifier abuse throttling.
-- Applied to Neon 2026-11-07, additive migration.
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS token_version INTEGER NOT NULL DEFAULT 0 CHECK (token_version >= 0);
CREATE TABLE IF NOT EXISTS public.auth_rate_limits (
 subject_hash CHAR(64) PRIMARY KEY,
 window_started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 failed_count INTEGER NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
 locked_until TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ix_auth_rate_limits_expiry ON public.auth_rate_limits(window_started_at,locked_until);
