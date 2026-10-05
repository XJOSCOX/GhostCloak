# Storage boundary

**CURRENT source:** `PostgresDatabase` persists account, device, public prekey,
allocation, mailbox, receipt and attachment metadata using the reviewed V001–V008
migration chain. `MailboxService` and `RetentionWorker` enforce expiry and
retention. Attachment bodies are encrypted client-side and stored through
`BlobService` in the separately configured blob directory. See the
[deployment runbook](../../infrastructure/DEPLOYMENT.md) and
[backup policy](../../infrastructure/BACKUP_POLICY.md).

**HISTORICAL/test fixture:** `OpaqueMailbox` is bounded and in-memory. It
still supports local opacity tests, but is not the production persistence
layer. The server stores encrypted envelopes, not endpoint decryption keys.
