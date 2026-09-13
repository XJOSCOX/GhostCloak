# Phase 1I.2 manual deployment

Prepared locally only. **Backend migration V003 and a VPS release are required; neither has been deployed.** No Cloudflare, firewall, tunnel, DNS or production database action was performed. No user-facing media entry point is enabled.

## Exact next operator step

Build the reviewed release locally from `Android/`:

```powershell
.\gradlew.bat :backend:installDist --dependency-verification strict
```

Review/copy the generated `backend/build/install/backend/` distribution to a new root-owned immutable directory under `/opt/ghostcloak/releases/`. Record its actual release directory as `release_dir` in the following operator shell. Do not use an unreviewed source checkout on the server.

Before service downtime, confirm backup recovery for the current PostgreSQL schema, free disk, current mailbox TTL and the current private ingress configuration. Require at least a 10 GiB blob budget plus 5 GiB free headroom, in addition to OS/database/log needs. Seven-day blob retention is designed for the default 24-hour mailbox TTL; review longer mailbox TTL before enabling. Host snapshots must exclude ephemeral blobs. If these checks fail, leave the blob feature disabled.

## Manual migration and service release

After installing the reviewed release, set `release_dir` to its exact absolute path. Prepare `/etc/ghostcloak/migration.env` as a root-owned 0600 file containing the existing required configuration with the dedicated `ghostcloak_migrator` credentials, using the established secret-editing procedure. Do not copy those credentials into the running service environment or shell arguments. Then:

```sh
test -x "$release_dir/bin/backend"
sudo systemctl stop ghostcloak
sudo systemd-run --wait --pipe --collect --unit=ghostcloak-migrate-v003 \
  -p User=ghostcloak -p Group=ghostcloak \
  -p EnvironmentFile=/etc/ghostcloak/migration.env \
  "$release_dir/bin/backend" migrate
```

Use the dedicated migration-capable owner role; do not broaden the running service role or print secrets. V003 is additive, transactional and recorded with its checksum in schema_history. Re-running the migration validates the same checksum rather than reapplying SQL. Accounts/mailboxes/receipts are preserved. Startup requires versions 1, 2 and 3 even when blob routes remain disabled. Remove the temporary migration environment file after successful validation according to the existing secret-retention procedure.

The runtime role needs SELECT/INSERT/UPDATE/DELETE on `attachment_blobs` and `attachment_budgets`, using the existing database-role/default-privilege procedure. No public role access. Validate migration exit status and grants before changing `/opt/ghostcloak/current` to the new release through the existing release procedure.

## Optional blob activation after review

```sh
sudo install -d -o ghostcloak -g ghostcloak -m 0700 /var/lib/ghostcloak/attachments
sudo install -d -m 0755 /etc/systemd/system/ghostcloak.service.d
```

Create `/etc/systemd/system/ghostcloak.service.d/attachments.conf` with:

```ini
[Service]
ReadWritePaths=/var/lib/ghostcloak/attachments
```

Add this nonsecret setting to the existing root-owned 0600 environment file:

```text
GHOSTCLOAK_ATTACHMENTS_DIR=/var/lib/ghostcloak/attachments
```

Without this setting the new binary exposes no blob routes. An invalid/inaccessible configured directory fails startup. Files use opaque IDs and 0600 permissions; the process takes an exclusive directory lock, so only one backend instance may own this local storage. Do not use a shared directory across independently running instances.

Review [attachments.nginx.conf.fragment](attachments.nginx.conf.fragment) inside the **existing private** server block; do not replace it with the old public TLS server template. It changes only the attachment path's body/time limits and preserves visitor-header stripping. Session authorization and the independent read-capability header must reach the backend; neither may be logged. No proxy buffering/cache/compression or static-file directory exposure. Preserve existing mailbox location limits, private listener and tunnel design.

```sh
sudo nginx -t -c /etc/ghostcloak-origin/nginx.conf
sudo systemctl daemon-reload
sudo systemctl start ghostcloak
sudo systemctl restart ghostcloak-origin
```

Verify loopback health first, then synthetic authenticated reserve/upload/download through the existing tunnel, including a slow transfer and maximum body. The edge may impose stricter size/time limits; do not claim 660-second end-to-end support until measured. Do not change Cloudflare architecture or bypass a failed tunnel. Verify missing capability and missing session rejection, no redirects/compression, file modes, quota rejection, restart and expired-partial cleanup. Public media UI still remains absent.

## Retention, backups and rollback

The existing in-process retention worker performs bounded cleanup every 30 seconds; no new cron/systemd timer. Partial TTL is one hour; complete TTL seven days, independent of message timers/view-once, downloads or ACKs. Client-only references distinguish completed orphans from referenced blobs; the server deliberately cannot. Access rejects expired rows before physical cleanup. Health fails on cleanup errors. Inspect operational health without dumping request paths or headers.

Exclude `/var/lib/ghostcloak/attachments` from application backups and host snapshots. PostgreSQL metadata follows the existing backup policy. Restoration reconciles missing/expired files before activation; loss of ephemeral blobs is an accepted availability tradeoff. Do not promise secure physical erasure.

Preferred rollback: remove `GHOSTCLOAK_ATTACHMENTS_DIR`, remove the reviewed attachment nginx location, validate/reload nginx and restart the schema-v3 binary with blob routes disabled. Preserve its V003 tables/files until a reviewed cleanup decision. **The old binary rejects schema version 3.** Reverting to an old binary requires a reviewed maintenance-window restore of the pre-migration database and matching release, losing post-backup changes; do not drop V003/schema_history entries casually. Keep blob backups excluded and record any restored metadata cleanup obligations.

Phase 1I.3 still needs picker/SAF, sanitization, attachment UI, explicit download/view and external-viewer privacy. Voice recording/playback, video and view-once are later phases.
