# Backup policy

No cloud provider or backup job is configured by this phase. A database backup contains usernames, public keys, routing IDs, token hashes, timestamps, rate-limit buckets, dedupe metadata and ciphertext. These are sensitive even though endpoint secrets and plaintext never belong in PostgreSQL.

Before production migrations, make an encrypted, access-controlled backup and verify restore in an isolated cluster. Use `pg_dump` with a matching supported client, an owner-controlled encryption recipient/key outside the VPS and Git, restrictive file permissions and a dedicated backup role. Do not pass passwords or encryption secrets on command lines. Do not leave an intermediate unencrypted dump on persistent storage. Disable shell tracing and account for WAL, snapshots, replicas and temporary files.

Suggested initial retention: seven daily encrypted backups, no indefinite archive. Restrict restore authorization and object access; test a restore monthly. Restored service stays private until schema checks and cleanup complete. A restore can resurrect expired/ACKed ciphertext, dedupe and session state: run retention, revoke all restored access sessions, and require fresh login before reopening ingress. Endpoint replay protection still applies, but a backup rollback cannot guarantee seamless delivery.

Live-row deletion does not erase WAL, pages, SSD remanence, replicas or prior backups. Expiry therefore is not assured physical deletion. Publish the actual retention before accepting users. A compromised backup reveals metadata and supports offline attacks on hashes; strong random bearer tokens limit useful token guessing. Encryption keys must be rotated and independently recoverable by the owner without placing endpoint keys on the server.
