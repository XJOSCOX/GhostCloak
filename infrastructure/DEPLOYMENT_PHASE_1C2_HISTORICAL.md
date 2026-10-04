# Phase 1C.2 deployment package

For the current Phase 1I.2 release, use [ATTACHMENTS_DEPLOYMENT.md](ATTACHMENTS_DEPLOYMENT.md): additive V003 is required, and blob activation is explicit/manual. Its current migration requirements supersede the historical V002 startup note below. No remote deployment is claimed.

Phase 1E.1 upgrade: use the [current rollout review](../SECURITY_REVIEW_PHASE_1E1.md). The new migration command validates V001 and applies/checksums additive V002 for ACK receipts. Normal startup now requires both versions. Deploy the matching backend before updated Android clients; the older binary cannot run against schema version 2.

Prepared for owner review only. Do not SSH, deploy, issue certificates or change DNS/firewalls as part of local validation. This phase does not implement Phase 1D or anonymity.

## Build and configuration

From `Android`, run `./gradlew :backend:installDist` with strict verification. The resulting `backend/build/install/backend` directory contains the production launcher and pinned runtime. `:backend:runLocal` remains the explicit in-memory development fixture; production main requires PostgreSQL and never falls back.

On the reviewed host, the owner creates a system service account (`useradd --system --home /nonexistent --shell /usr/sbin/nologin ghostcloak`) and installs the immutable release under `/opt/ghostcloak/releases/RELEASE`, root-owned and readable/executable by ghostcloak. Point `/opt/ghostcloak/current` to that release. Do not run Gradle or a source checkout as the production service.

Copy `.env.example` to `/etc/ghostcloak/ghostcloak.env`, root:root mode 0600; fill it with a private JDBC URL such as `jdbc:postgresql://127.0.0.1:5432/ghostcloak`, service role, independently generated random password (at least 20 characters) and the owner's HTTPS origin. The service manager reads the file before changing user. Never embed secrets in Android, Git, shell arguments/history or support logs. The service environment can be read by root; the host is trusted for metadata and availability.

## Database and migration

Review the PostgreSQL and pg_hba examples for the target cluster. Apply `database-roles.sql` once as administrator, set both passwords using interactive `\password`, and grant no service superuser/CREATE privileges. Keep administrator access and the migrator secret separate from runtime configuration.

Create a separate root-readable migration environment file with the migrator role. After encrypted backup/restore rehearsal, run the release's `bin/backend migrate` using that environment through a one-shot systemd unit (same `EnvironmentFile`/non-root process isolation as the service, with `ExecStart` ending in `migrate`). This performs only checksummed V001 under a transaction/advisory lock. Review its exit status before continuing; failure rolls back. No destructive reset/auto-repair occurs. Then execute the commented post-migration grants in `database-roles.sql` as owner. The application receives DML on domain tables and SELECT only on schema_history. Remove runtime access to the migrator secret.

Normal startup validates version and checksum without creating tables. Missing schema, changed migrations, unsafe configuration or unavailable DB prevents startup. Future upgrades need a new reviewed numbered migration and backup; never edit an applied migration. Rollback application binaries only when schema compatibility has been explicitly verified; otherwise restore privately and follow BACKUP_POLICY.md.

Install `ghostcloak.service`, run `systemd-analyze verify` on the actual Linux host, then owner-reviewed `systemctl daemon-reload` and `systemctl start ghostcloak`. Inspect exit/health state, not secret environment dumps. Confirm loopback health. Install the reviewed nginx template with owner certificates and run `nginx -t` before reload. Follow TLS.md and VPS_HARDENING.md for external checks. No remote deployment is claimed by local tests.

## Android

Build with `-PghostcloakApiOrigin=https://OWNER_HOSTNAME` after substituting the exact reviewed domain. Empty configuration keeps online controls disabled. Android uses HTTPS platform trust and Keystore P-256 signing; no editable server URL or cleartext override is exposed.

Create a local identity, use Settings > Connect, and add contacts by exact network username. Both correspondents should add each other and compare safety numbers before exchanging messages. Use Sync to fetch/ACK and retry queued sends; Publish prekey replenishes the public bundle. There is no background delivery/push. Server acceptance means queued, not read. The existing local demo remains separate.

Phase 1C.1 prototype records with exported auth credentials fail explicitly with `legacy_auth_requires_reset`. There is no silent credential replacement or production recovery endpoint. For a disposable development install, first explicitly abandon/revoke its server session and retire its account in an isolated development database, then clear the prototype app data and register a new identity/account. This destroys local history and changes safety numbers; notify test correspondents and re-verify. Do not clear data on a valuable install as a repair. A registered Keystore alias that disappears fails closed and is never silently regenerated. Hardware backing depends on device; non-exportability is enforced, StrongBox is not guaranteed.

## Validation

Unit tests: `./gradlew :test-support:test`. PostgreSQL integration: create an isolated database named exactly `ghostcloak_test`, provide GHOSTCLOAK_TEST_DATABASE_URL/USER/PASSWORD via private environment, then `./gradlew :test-support:postgresTest`. Tests create/drop only generated schemas in that explicit database; never point them at production. See STAGING_TEST.md for HTTPS coverage. Android emulator tests use `:storage:connectedDebugAndroidTest :app:connectedDebugAndroidTest` with the intended emulator serial selected explicitly.

The first repository adapter serializes domain transactions using a database advisory lock across service instances. This preserves existing check/write invariants but limits throughput and is not a scaling claim. JDBC work is bounded to eight connections per process with timeouts. Load testing, host-specific systemd validation, externally trusted HTTPS and external firewall probes remain operator staging gates.
