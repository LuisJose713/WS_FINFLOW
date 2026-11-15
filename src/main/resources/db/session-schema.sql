-- Additive, idempotent session migration. Raw refresh credentials are never stored.
CREATE TABLE IF NOT EXISTS auth_refresh_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    family_id UUID NOT NULL,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_version INTEGER NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by_hash VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS auth_refresh_sessions_family_idx ON auth_refresh_sessions(family_id);
CREATE INDEX IF NOT EXISTS auth_refresh_sessions_expiry_idx ON auth_refresh_sessions(expires_at);

CREATE TABLE IF NOT EXISTS user_ui_preferences (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    card_id VARCHAR(80) NOT NULL,
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (user_id, card_id)
);

CREATE TABLE IF NOT EXISTS finflow_push_subscriptions (
 user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 endpoint TEXT PRIMARY KEY,
 p256dh VARCHAR(256) NOT NULL,
 auth VARCHAR(256) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS finflow_push_subscriptions_user_idx ON finflow_push_subscriptions(user_id);
CREATE TABLE IF NOT EXISTS finflow_push_delivery_days (
 user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 delivery_day DATE NOT NULL,
 PRIMARY KEY(user_id, delivery_day)
);

-- Verified Google account-to-user linking. No automatic merging by email.
-- Subject is Google's immutable "sub"; user is the existing FinFlow account.
CREATE TABLE IF NOT EXISTS public.google_account_links (
    google_sub VARCHAR(255) PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES public.users(id) ON DELETE CASCADE,
    google_email VARCHAR(254) NOT NULL,
    linked_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_google_account_links_user_id ON public.google_account_links(user_id);
