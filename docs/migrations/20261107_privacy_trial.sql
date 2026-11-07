-- Privacy consent is NEVER backfilled: existing users must actively opt in.
CREATE TABLE IF NOT EXISTS public.privacy_consents (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
 policy_version VARCHAR(40) NOT NULL,
 marketing_opt_in BOOLEAN NOT NULL DEFAULT false,
 source VARCHAR(24) NOT NULL CHECK(source IN ('SIGNUP','LEGACY_LOGIN')),
 accepted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(user_id,policy_version)
);
CREATE INDEX IF NOT EXISTS idx_privacy_consents_user ON public.privacy_consents(user_id,accepted_at DESC);

CREATE TABLE IF NOT EXISTS public.privacy_rights_requests (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
 request_type VARCHAR(20) NOT NULL CHECK(request_type IN ('ACCESS','RECTIFY','DELETE','REVOKE')),
 description VARCHAR(500),
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.billing_trials (
 user_id BIGINT PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
 started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 expires_at TIMESTAMPTZ NOT NULL CHECK(expires_at > started_at)
);
