-- Additive, idempotent marketing preferences audit trail.
-- Original privacy_consents and privacy_rights_requests already exist.
-- This migration does not backfill any consent or invent marketing permission.
CREATE TABLE IF NOT EXISTS public.privacy_marketing_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    policy_version VARCHAR(40) NOT NULL,
    marketing_opt_in BOOLEAN NOT NULL,
    source VARCHAR(24) NOT NULL CHECK (source IN ('SIGNUP','CONSENT','PREFERENCE')),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_privacy_marketing_events_user
    ON public.privacy_marketing_events(user_id, recorded_at DESC);
