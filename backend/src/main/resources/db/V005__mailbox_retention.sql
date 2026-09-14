ALTER TABLE message_deduplication ADD COLUMN mailbox_expires_at bigint NOT NULL DEFAULT 0;
UPDATE mailbox_messages SET expires_at = received_at + 604800000;
UPDATE message_deduplication d SET mailbox_expires_at = m.expires_at FROM mailbox_messages m WHERE m.id = d.server_message_id;
CREATE INDEX IF NOT EXISTS mailbox_expiry_cleanup_idx ON mailbox_messages(expires_at, id);
