CREATE TABLE accounts (
 id varchar(36) PRIMARY KEY, username varchar(24) NOT NULL UNIQUE,
 device_id varchar(36) NOT NULL UNIQUE,
 CHECK (username ~ '^[a-z0-9][a-z0-9_.]{2,23}$')
);
CREATE TABLE devices (
 id varchar(36) PRIMARY KEY, account_id varchar(36) NOT NULL UNIQUE REFERENCES accounts(id),
 routing_id varchar(36) NOT NULL UNIQUE, auth_public_key bytea NOT NULL UNIQUE,
 identity_public_key bytea NOT NULL,
 CHECK (octet_length(auth_public_key) BETWEEN 80 AND 128), CHECK (octet_length(identity_public_key)=33)
);
ALTER TABLE accounts ADD CONSTRAINT account_device_fk FOREIGN KEY(device_id) REFERENCES devices(id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE auth_challenges (
 id varchar(36) PRIMARY KEY, account_id varchar(36) NOT NULL, device_id varchar(36) NOT NULL,
 random_bytes bytea NOT NULL CHECK(octet_length(random_bytes)=32), expires_at bigint NOT NULL,
 audience varchar(100) NOT NULL, purpose varchar(8) NOT NULL CHECK(purpose IN ('register','login')),
 registration_hash bytea NOT NULL CHECK(octet_length(registration_hash) IN (0,32))
);
CREATE INDEX challenge_expiry ON auth_challenges(expires_at);
CREATE TABLE access_sessions (
 token_hash char(64) PRIMARY KEY, device_id varchar(36) NOT NULL UNIQUE REFERENCES devices(id), expires_at bigint NOT NULL
);
CREATE INDEX session_expiry ON access_sessions(expires_at);
CREATE TABLE signed_prekeys (
 device_id varchar(36) NOT NULL REFERENCES devices(id), key_id integer NOT NULL CHECK(key_id>0),
 public_key bytea NOT NULL CHECK(octet_length(public_key)=33), signature bytea NOT NULL CHECK(octet_length(signature)=64),
 PRIMARY KEY(device_id,key_id)
);
CREATE TABLE one_time_prekeys (
 device_id varchar(36) NOT NULL REFERENCES devices(id), key_id integer NOT NULL CHECK(key_id>0),
 public_key bytea CHECK(public_key IS NULL OR octet_length(public_key)=33), PRIMARY KEY(device_id,key_id)
);
CREATE TABLE pq_prekeys (
 device_id varchar(36) NOT NULL REFERENCES devices(id), key_id integer NOT NULL CHECK(key_id>0),
 public_key bytea CHECK(public_key IS NULL OR octet_length(public_key)=1569),
 signature bytea CHECK(signature IS NULL OR octet_length(signature)=64), PRIMARY KEY(device_id,key_id)
);
CREATE TABLE prekey_bundles (
 device_id varchar(36) NOT NULL REFERENCES devices(id), ec_id integer NOT NULL, pq_id integer NOT NULL, signed_id integer NOT NULL,
 registration_id integer NOT NULL CHECK(registration_id BETWEEN 1 AND 16383), position integer NOT NULL CHECK(position BETWEEN 0 AND 31),
 PRIMARY KEY(device_id,ec_id), UNIQUE(device_id,pq_id), UNIQUE(device_id,position),
 FOREIGN KEY(device_id,ec_id) REFERENCES one_time_prekeys(device_id,key_id),
 FOREIGN KEY(device_id,pq_id) REFERENCES pq_prekeys(device_id,key_id),
 FOREIGN KEY(device_id,signed_id) REFERENCES signed_prekeys(device_id,key_id)
);
CREATE TABLE mailbox_messages (
 id varchar(36) PRIMARY KEY, recipient_routing_id varchar(36) NOT NULL REFERENCES devices(routing_id),
 encrypted_envelope bytea NOT NULL CHECK(octet_length(encrypted_envelope) BETWEEN 1 AND 131072),
 received_at bigint NOT NULL, expires_at bigint NOT NULL CHECK(expires_at>received_at)
);
CREATE INDEX mailbox_recipient ON mailbox_messages(recipient_routing_id,received_at,id);
CREATE INDEX mailbox_expiry ON mailbox_messages(expires_at);
CREATE TABLE message_deduplication (
 id varchar(73) PRIMARY KEY, sender_device_id varchar(36) NOT NULL REFERENCES devices(id),
 payload_hash bytea NOT NULL CHECK(octet_length(payload_hash)=32), server_message_id varchar(36) NOT NULL,
 expires_at bigint NOT NULL
);
CREATE INDEX dedupe_sender ON message_deduplication(sender_device_id);
CREATE INDEX dedupe_expiry ON message_deduplication(expires_at);
CREATE TABLE rate_limits (
 operation varchar(16) NOT NULL, principal varchar(64) NOT NULL, window_start bigint NOT NULL, count integer NOT NULL CHECK(count>0),
 PRIMARY KEY(operation,principal)
);
