CREATE TABLE attachment_blobs (
    id char(64) PRIMARY KEY CHECK (id ~ '^[0-9a-f]{64}$'),
    owner_account text NOT NULL REFERENCES accounts(id),
    owner_device text NOT NULL REFERENCES devices(id),
    encrypted_length bigint NOT NULL CHECK (encrypted_length BETWEEN 1 AND 27262976),
    ciphertext_digest bytea NOT NULL CHECK (octet_length(ciphertext_digest)=32),
    capability_hash bytea NOT NULL CHECK (octet_length(capability_hash)=32),
    created_at bigint NOT NULL,
    expires_at bigint NOT NULL,
    complete boolean NOT NULL DEFAULT false,
    uploading boolean NOT NULL DEFAULT false
);
CREATE INDEX attachment_expiry ON attachment_blobs(expires_at);
CREATE INDEX attachment_owner ON attachment_blobs(owner_account);
CREATE TABLE attachment_budgets (
    id text PRIMARY KEY REFERENCES accounts(id),
    minute_bucket bigint NOT NULL,
    requests integer NOT NULL,
    uploaded bigint NOT NULL,
    downloaded bigint NOT NULL,
    day_bucket bigint NOT NULL
);
