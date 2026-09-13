# Existing-device recovery — manual release only

**Backend release and V004 migration are required. Nothing was deployed automatically.** Attachment/V003 deployment is already live according to the operator. Preserve its directory, environment, grants, nginx locations and service drop-ins. Do not rerun attachment activation or replace the live nginx configuration with a repository template.

## Next manual step

From `Android/`, build the reviewed commit:

```powershell
.\gradlew.bat :backend:installDist --dependency-verification strict
```

Copy `backend/build/install/backend/` into a new reviewed, root-owned immutable release directory under `/opt/ghostcloak/releases/` using the existing deployment procedure. Record that exact directory in `release_dir`. Confirm the existing database backup/recovery procedure before downtime. Keep all current service configuration, including attachment settings. Do not print or put database credentials in shell arguments.

## V004 and backend

V004 only replaces `auth_challenges_purpose_check` to allow `recover` alongside `register` and `login`. Existing columns, account/device ownership, keys, sessions, message and blob tables are not rewritten. No additional runtime grants or storage directory are required. The existing version/checksum transaction applies it once; subsequent migration runs validate it. This backend requires versions 1–4.

Using the established dedicated migration-role credential procedure, prepare root-owned mode-0600 `/etc/ghostcloak/migration.env` (never use the migration role in the running service). Then manually on the VPS:

```sh
test -x "$release_dir/bin/backend"
sudo systemctl stop ghostcloak
sudo systemd-run --wait --pipe --collect --unit=ghostcloak-migrate-v004 \
  -p User=ghostcloak -p Group=ghostcloak \
  -p EnvironmentFile=/etc/ghostcloak/migration.env \
  "$release_dir/bin/backend" migrate
```

Check successful exit and checksum validation before switching `/opt/ghostcloak/current` to that reviewed release through the existing release procedure. Remove the temporary migration environment file according to the existing secret-retention procedure. Keep the restricted runtime role and all current attachment configuration.

## Private ingress

The existing mailbox route allowlist must additionally allow exactly `/v1/auth/recovery/challenge` and `/v1/auth/recovery/verify`. In its regex, change `auth/(challenge|verify|revoke)` to `auth/(challenge|verify|revoke|recovery/(challenge|verify))`. See the one-line change in `tunnel/nginx-origin.conf.template`. Merge only that change: preserve the live attachment locations, header stripping, no-logging, body caps, loopback binding and timeouts. No Cloudflare/DNS/tunnel architecture change is required.

```sh
sudo nginx -t -c /etc/ghostcloak-origin/nginx.conf
sudo systemctl start ghostcloak
sudo systemctl restart ghostcloak-origin
```

Verify service health and normal Phone B text synchronization before Phone A recovery. No production account registration or synthetic recovery request is required for deployment health checks. A recovery 404 requires checking this rollout; the client must not fall back to registration.

## Rollback

Do not launch the previous backend against version 4: its exact-version check expects 3. Prefer a forward correction. If rollback is necessary, stop the backend and use the dedicated migration role and reviewed backup procedure. In one transaction remove only short-lived `auth_challenges` rows with purpose `recover`, restore the purpose constraint to `('register','login')`, then remove only version 4 from `schema_history`. Validate versions 1–3/checksums and restart the prior reviewed V003 backend. Remove only the two recovery allowlist paths if rolling back ingress. Do not alter ownership, keys, mailbox data, or attachment tables/configuration. Successfully recovered client bindings remain the original valid bindings and can use ordinary login; unrecovered clients must wait for a working recovery backend.

## Physical Phone A

After deployment, follow `Android/DEVELOPMENT.md`. The original private device-auth key must match the server's existing key; a key-entry-present diagnostic alone cannot guarantee this. Failure is not permission for key replacement or username-based recovery.
