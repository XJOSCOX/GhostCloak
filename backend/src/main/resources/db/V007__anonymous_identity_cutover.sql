-- Pre-release reset: every listed row is identity-bound disposable test state.
-- Never execute automatically against an unreviewed deployed database.
DELETE FROM attachment_budgets;
DELETE FROM attachment_blobs;
DELETE FROM message_deduplication;
DELETE FROM mailbox_messages;
DELETE FROM prekey_bundles;
DELETE FROM one_time_prekeys;
DELETE FROM pq_prekeys;
DELETE FROM signed_prekeys;
DELETE FROM access_sessions;
DELETE FROM auth_challenges;
DELETE FROM rate_limits;
DELETE FROM devices;
DELETE FROM accounts;
ALTER TABLE accounts DROP COLUMN username;
ALTER TABLE accounts ADD COLUMN ghostcloak_id varchar(12) COLLATE "C" NOT NULL UNIQUE;
ALTER TABLE accounts ADD CONSTRAINT accounts_ghostcloak_id_format
    CHECK (ghostcloak_id ~ '^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{12}$');
