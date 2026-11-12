-- Run on the production PostgreSQL database before enabling FINFLOW_VAPID_*.
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
