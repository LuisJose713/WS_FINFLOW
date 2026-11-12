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
