-- E2EE store only. Run in a Neon QA branch first.
-- CAUTION: this does NOT migrate or remove existing plaintext tables.
-- No decryption key, item type, amount, title, date or category is stored here.
CREATE TABLE IF NOT EXISTS private_vault_entries (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    entry_id UUID NOT NULL,
    nonce BYTEA NOT NULL CHECK (octet_length(nonce) = 12),
    ciphertext BYTEA NOT NULL CHECK (octet_length(ciphertext) BETWEEN 16 AND 524288),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, entry_id)
);
-- The (user_id, entry_id) primary key already provides the pagination index.
COMMENT ON TABLE private_vault_entries IS
    'Opaque client-encrypted AES-256-GCM records; encryption keys must never reach API or DB';
