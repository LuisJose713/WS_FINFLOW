-- New-email proof before any login email can change. Encrypted transport to SMTP
-- and strong SMTP credentials must be configured separately, never committed.
CREATE TABLE IF NOT EXISTS public.profile_email_challenges(
  user_id BIGINT PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
  new_email VARCHAR(254) NOT NULL,
  code_hmac CHAR(64) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
  requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS public.profile_email_request_history(
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
  requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_profile_email_request_history_user_date
  ON public.profile_email_request_history(user_id,requested_at DESC);
