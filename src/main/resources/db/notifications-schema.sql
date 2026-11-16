-- Additive notification scheduling. Message content is encrypted by FieldCipher.
CREATE TABLE IF NOT EXISTS finflow_push_delivery_slots (
 user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 delivery_day DATE NOT NULL,
 delivery_hour INTEGER NOT NULL CHECK (delivery_hour IN (10,16)),
 PRIMARY KEY(user_id,delivery_day,delivery_hour)
);
CREATE TABLE IF NOT EXISTS finflow_notification_campaigns (
 id UUID PRIMARY KEY,
 created_by BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 title_cipher TEXT NOT NULL,
 body_cipher TEXT NOT NULL,
 audience VARCHAR(8) NOT NULL CHECK (audience IN ('SELF','ALL')),
 scheduled_at TIMESTAMPTZ NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED'
   CHECK (status IN ('SCHEDULED','SENDING','SENT','FAILED','CANCELLED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 finished_at TIMESTAMPTZ,
 accepted_web INTEGER NOT NULL DEFAULT 0,
 failed_web INTEGER NOT NULL DEFAULT 0,
 native_devices INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS finflow_notification_due_idx
 ON finflow_notification_campaigns(scheduled_at) WHERE status = 'SCHEDULED';
CREATE TABLE IF NOT EXISTS finflow_notification_devices (
 user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 device_hash VARCHAR(64) NOT NULL,
 platform VARCHAR(8) NOT NULL CHECK (platform IN ('ANDROID','IOS')),
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 subscribed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 PRIMARY KEY(user_id, device_hash)
);
CREATE TABLE IF NOT EXISTS finflow_notification_receipts (
 campaign_id UUID NOT NULL REFERENCES finflow_notification_campaigns(id) ON DELETE CASCADE,
 user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 device_hash VARCHAR(64) NOT NULL,
 acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 PRIMARY KEY(campaign_id, user_id, device_hash)
);
