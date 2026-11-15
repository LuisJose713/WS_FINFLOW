-- Scoped to push delivery deduplication. No financial records are changed.
-- Independent hour buckets allow 10:00 and 16:00 reminders on the same date.
CREATE TABLE IF NOT EXISTS finflow_push_delivery_slots (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    delivery_day DATE NOT NULL,
    delivery_hour SMALLINT NOT NULL CHECK (delivery_hour IN (10, 16)),
    PRIMARY KEY(user_id, delivery_day, delivery_hour)
);
