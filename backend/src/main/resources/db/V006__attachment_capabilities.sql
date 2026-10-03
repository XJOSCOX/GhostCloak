ALTER TABLE prekey_bundles ADD COLUMN attachment_capability bytea NULL;
ALTER TABLE prekey_bundles ADD CONSTRAINT attachment_capability_size
    CHECK (attachment_capability IS NULL OR octet_length(attachment_capability) BETWEEN 1 AND 512);
