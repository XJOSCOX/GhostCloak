-- The existing expires_at column is the intrinsic ID expiry for V3 rows.
-- Legacy UUID receipts have no safe retirement deadline and are excluded.
CREATE INDEX dedupe_v3_retirement ON message_deduplication(expires_at,id)
 WHERE substring(id from 38 for 2)='s3';
CREATE INDEX dedupe_v3_live_sender ON message_deduplication(sender_device_id,expires_at)
 WHERE substring(id from 38 for 2)='s3';
