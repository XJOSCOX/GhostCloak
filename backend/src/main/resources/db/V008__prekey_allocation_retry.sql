-- Additive, short-lived public prekey allocation retry evidence. No account reset.
CREATE TABLE prekey_allocations (
 id varchar(73) PRIMARY KEY,
 requester_device varchar(36) NOT NULL REFERENCES devices(id),
 target_device varchar(36) NOT NULL REFERENCES devices(id),
 bundle bytea CHECK (bundle IS NULL OR octet_length(bundle) BETWEEN 1 AND 4096),
 response_expires_at bigint NOT NULL,
 expires_at bigint NOT NULL
);
CREATE INDEX prekey_allocations_expiry ON prekey_allocations(expires_at,id);
CREATE INDEX prekey_allocations_response_expiry ON prekey_allocations(response_expires_at,id) WHERE bundle IS NOT NULL;
CREATE INDEX prekey_allocations_requester ON prekey_allocations(requester_device,target_device,expires_at);
