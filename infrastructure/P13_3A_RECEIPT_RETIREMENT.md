# P13.3A receipt retirement and capacity gate

This phase requires backend deployment and V009 migration. It does not deploy itself, change nginx, or enable group chat. P13.3B backend and updated clients must already be deployed and validated. V009 adds two partial indexes to the existing `message_deduplication` table; it changes no existing row or privilege grant. The running `ghostcloak_app` role must retain SELECT, INSERT, UPDATE and DELETE on that table. The dedicated migrator role creates indexes and writes schema history.

## Receipt lifecycle and compatibility

An expiring V3 ID carries its immutable deadline. SEND accepts it at `serverNow == expiresAt` and rejects it at `serverNow > expiresAt` before mailbox lookup. A receipt is ACTIVE until recipient ACK or mailbox expiry, then ACKNOWLEDGED or EXPIRED_UNDELIVERED. After the intrinsic deadline it is RETIRABLE irrespective of terminal state; the independent mailbox row can continue until its own seven-day deadline. The worker deletes at most 128 such receipts every 30 seconds, within its transaction, using the V009 partial `(expires_at,id)` index. Direct calls are safe with or without an enclosing transaction. Repeated cleanup is idempotent. This yields up to 256 receipts/minute or 368,640/day when a backlog exists. A failed cleanup makes worker health false; a later successful cycle restores it. Public `/health` remains generic.

Legacy UUID receipts have no intrinsic deadline. They are neither in the partial retirement index nor deleted by this worker. New legacy SEND remains enabled during transition; its historical 1,024-per-sender/10,000-global caps remain, separate from V3, so accumulated legacy rows cannot impose a lifetime cap on updated V3 clients. Residual legacy delivery metadata persists until an explicitly approved cutoff and separately proven retention plan. A future cutoff can reject **new** UUID SEND only after active-client upgrade evidence; it is not active here. V009 does not rewrite IDs or backfill a guessed expiry.

## Limits and model

| Transport control | Launch value |
| --- | ---: |
| Active V3 receipts per sender | 16,384 |
| Active V3 receipts globally | 100,000 |
| Legacy UUID receipts per sender / globally | 1,024 / 10,000 |
| Mailbox rows per recipient / globally | 512 / 20,000 |
| Mailbox ciphertext bytes per recipient | 16 MiB |
| FETCH/ACK batch | 8 |
| SEND rate | 60/minute/sender |
| Long-horizon transport budget | Deferred; live caps and intrinsic expiry bound V3 storage |

Only V3 rows whose intrinsic expiry is at or after server time count toward live V3 caps. Expired rows release capacity immediately even if cleanup is backlogged. Sender and global checks remain inside the serialized SEND transaction. The 14-day maximum V3 horizon bounds active receipt accumulation. A 16,384 sender cap permits roughly 1,170 physical SENDs/day over that horizon; 60/minute remains the burst control. The global 100,000 limit is an availability tradeoff, not a guarantee that distributed senders cannot fill it. The mailbox byte cap remains important: large encrypted payloads hit 16 MiB before the row cap. No plaintext group-aware accounting is added.

For a five-member group, one logical text is four SENDs and one inbound text from each of four peers is four mailbox rows. At 20 texts/day/member across five groups, a member sends 400 transport submissions/day (5,600 over 14 days) and receives 400 rows/day while offline; 512 rows leaves 112 for direct traffic. At 100 texts/day/member in **one** group, the same 400/day and 400 inbound/day fit. At 100 texts/day/member across five groups, 2,000 inbound rows/day **do not** fit; this is a known high-volume limit, not a supported seven-day-offline case. A seven-day offline period at the ordinary five-group rate would accumulate 2,800 rows and hit the mailbox cap; clients must catch up sooner. A burst of 10 group texts uses 40 SENDs, while 20 uses 80 and must span rate windows. A full 512-row mailbox requires up to 64 eight-item FETCH cycles, with normal ACKs and the existing FETCH rate budget. Group text launch should disclose these limits and test the user experience before enabling the feature.

## Isolated PostgreSQL sizing

Synthetic PostgreSQL 16 data used the production receipt columns and all five indexes, with 70-character V3-shaped IDs and 32-byte hashes. Measurements are local warm-cache `EXPLAIN ANALYZE` observations, not a VPS performance promise; table bloat, WAL, backups and concurrent load add cost.

| Rows | Table | Indexes | ID lookup | ACK update | 128-row cleanup |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 10,000 | 2.42 MB | 2.35 MB | 0.016 ms | 0.129 ms | 0.883 ms (100 expired rows) |
| 50,000 | 12.05 MB | 11.63 MB | 0.019 ms | 0.092 ms | 1.436 ms |
| 100,000 | 24.13 MB | 22.47 MB | 0.026 ms | 0.062 ms | 1.448 ms |

The cleanup plan used `dedupe_v3_retirement` for the ordered deadline scan. At 100,000 receipts the compacted base table plus indexes was about 46.6 MB before WAL/backups. The separate global mailbox cap limits encrypted-envelope storage; it does not make 20,000 maximum-size envelopes affordable on every VPS. Monitor actual disk and health before enabling group text.

At 100,000 synthetic rows, the global live-count query took 8.687 ms and an indexed sender live-count took 0.095 ms on the same warm local instance. These checks remain on the serialized SEND path. Sustained production throughput and disk/WAL pressure still require monitoring; the local figures justify a bounded launch cap, not unlimited growth.

## Manual deployment plan (owner, not Codex)

1. Pin the reviewed commit and built backend distribution as an immutable release. Record the jar SHA-256 and packaged V009 SQL SHA-256. Check available database/WAL/backup disk space.
2. Take and verify a pre-V009 PostgreSQL backup. Follow the existing attachment backup policy for a deployment backup; V009 does not mutate attachment rows or files. Do not overwrite the production database during rehearsal.
3. Stop the current backend briefly so the V008 binary cannot encounter V009 and so the DDL lock is predictable. Pin the migration unit to the **new** immutable release and run the dedicated migrator once. Existing V001–V008 checksums must remain unchanged.
4. Verify schema history V009 checksum, both partial indexes, unchanged pre-migration row counts, and `ghostcloak_app` SELECT/INSERT/UPDATE/DELETE on `message_deduplication`. The index needs no new table grant.
5. Switch `/opt/ghostcloak/current` to the new release and start the service. Confirm the runtime classpath points to it; check generic `/health` through loopback and public ingress, normal direct SEND/FETCH/ACK, V3 late-retry rejection, worker health and bounded cleanup. Record only sanitized counts and timings.
6. Rollback cannot mean starting the old V008 binary against V009: it rejects unknown schema history. If rollback is necessary, use a separately reviewed forward-compatible binary or a rehearsed database restore with operational data-loss assessment. Do not delete the V009 history row ad hoc. Do not run receipt cleanup or migration manually against live data outside the reviewed deployment.

After the protected backup has been verified and `release_dir` set to the absolute new immutable release path, the established one-shot migration pattern is:

```sh
test -x "$release_dir/bin/backend"
sha256sum "$release_dir/lib/backend.jar"
sudo systemctl stop ghostcloak
sudo systemd-run --wait --pipe --collect --unit=ghostcloak-migrate-v009 \
  -p User=ghostcloak -p Group=ghostcloak \
  -p EnvironmentFile=/etc/ghostcloak/migration.env \
  "$release_dir/bin/backend" migrate
```

The operator must first confirm the installed distribution's actual jar path and pin `/etc/ghostcloak/migration.env` to the dedicated migrator, not the runtime role. Do not put credentials in arguments or logs. With the existing protected database connection procedure, read-only post-migration SQL is:

```sql
SELECT version, checksum FROM schema_history ORDER BY version;
SELECT indexname, indexdef FROM pg_indexes
 WHERE schemaname=current_schema() AND tablename='message_deduplication'
   AND indexname IN ('dedupe_v3_retirement','dedupe_v3_live_sender');
SELECT count(*) FILTER (WHERE substring(id from 38 for 2)='s3') AS v3_receipts,
       count(*) FILTER (WHERE substring(id from 38 for 2)<>'s3') AS legacy_receipts
 FROM message_deduplication;
SELECT has_table_privilege('ghostcloak_app','message_deduplication','SELECT') AS can_select,
       has_table_privilege('ghostcloak_app','message_deduplication','INSERT') AS can_insert,
       has_table_privilege('ghostcloak_app','message_deduplication','UPDATE') AS can_update,
       has_table_privilege('ghostcloak_app','message_deduplication','DELETE') AS can_delete;
```

Compare sanitized pre/post row counts before starting the worker; after activation, a decrease only in expired V3 receipts is expected. Check `readlink -f /opt/ghostcloak/current`, `systemctl cat ghostcloak`, `systemctl status ghostcloak --no-pager`, loopback `/health`, then the public `/health` through the existing tunnel. Do not reveal tokens or record receipt IDs. Attachment storage and nginx are unchanged.

No nginx route change is required. P13.3 text may resume only after this backend migration and live health/capacity validation, updated-client compatibility, and a separate group UX test showing one-day ordinary offline catch-up and honest rate-limit behavior. The 100/day × five-group and seven-day-offline cases remain beyond the selected launch limits.
