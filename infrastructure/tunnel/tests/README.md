# Phase 1D.1 local validation

These checks prepare a deployment package; they never establish a Cloudflare Tunnel or contact api.ghostcloak.org. Do not substitute production credentials. The live staging results in CLOUDFLARE_TUNNEL.md were supplied by the owner.

## Linux ingress and service validator

Build the disposable validator image from the repository root:

```powershell
docker build -t ghostcloak-origin-validator:d1 infrastructure/tunnel/tests
```

Obtain the official **cloudflared 2026.9.0 Linux amd64** release binary into ignored `.research/d1/cloudflared`. It was selected for local syntax validation, not installed on the VPS. Its SHA-256 is `53b7a7a5420d188758d24341294acb0d1bca54296548ac05e38811a694ac6134`, verified against the GitHub release asset digest. [Official release](https://github.com/cloudflare/cloudflared/releases/tag/2026.9.0). cloudflared uses Apache-2.0; retain upstream notices when distributing it. Future deployed versions need fresh verification and the same checks. No new Gradle dependency was added.

Run from PowerShell:

```powershell
$fixture = Join-Path $PWD 'infrastructure/tunnel'
$parent = Join-Path $PWD 'infrastructure'
$tool = Join-Path $PWD '.research/d1'
docker run --rm --network none --cap-add NET_ADMIN `
  -e GHOSTCLOAK_LOCAL_VALIDATOR=network-none-container `
  --mount "type=bind,source=$fixture,target=/fixtures,readonly" `
  --mount "type=bind,source=$parent,target=/parent,readonly" `
  --mount "type=bind,source=$tool,target=/tool,readonly" `
  ghostcloak-origin-validator:d1
```

Use **network none**, never host networking, privileged mode or host firewall mounts. The validator also checks it is in a container with only loopback before changing its disposable namespace's nft table. No host ports are published. Only infrastructure files and the public tool binary directory are mounted, read-only; no SSH directory, full repository, home, socket credentials or real TLS material is mounted.

The script generates a one-day synthetic certificate inside the container, validates the actual nginx config, starts an opaque fake backend on 127.0.0.1:8787, and exercises all expected API routes over the TLS Unix socket. It checks header removal without breaking Authorization/body framing; wrong hosts/methods/paths/queries; oversized headers/bodies; minimal health; no visitor-string logs; socket permission denial; UID-filtered backend denial; allowed ingress-worker access; no public listeners; and repeatable atomic nft table loading. It then checks cloudflared config/flags/rule matching offline with a dummy UUID and no credentials JSON, and parses all systemd units. A dummy backend launcher and PostgreSQL unit are used only to satisfy unit-parser file/dependency checks, not to claim Linux backend/systemd execution.

Recorded local result (2026-09-10): **PASS**, Ubuntu 24.04 container, nginx 1.24.0 distribution package, cloudflared 2026.9.0. Container/image build is a test harness, not a recommended production Docker deployment. Production service isolation must still be exercised under the actual host's systemd, certificate paths, firewall manager and numeric UIDs.

## Application/database checks

From Android, run `./gradlew test` and the existing `:test-support:postgresTest` against a private, disposable database named `ghostcloak_test`, with GHOSTCLOAK_TEST_DATABASE_URL/USER/PASSWORD supplied outside Git. No schema/config/protocol dependency change is required. `OriginPrivacyTest` drives real Ktor with synthetic forwarded-IP headers across authentication, directory, send/dedupe, fetch and ACK. It checks source audit guards, fixed minimal health, log/principal capture and memory persistence. The same flow in `PostgresTest` scans every table for synthetic address strings and hex; existing Alice/Bob/Charlie/crash/concurrency suites still run.

Recorded results: **51 distinct JVM unit/acceptance tests pass** (50 test-support plus the existing app unit test), **8 PostgreSQL integration tests pass**. No Android runtime/UI code changed in this phase; the previous emulator validation remains historical rather than a new device claim.

Strict IDE validation: `./gradlew --no-configuration-cache -I gradle/verify-ide-sources.init.gradle verifyIdeSources`. **533 source/Javadoc attachments pass**, alongside discovered dependency graphs and Gradle source ZIP. Verification metadata and dependency versions are unchanged. Use the no-configuration-cache option because this audit init script is intentionally not configuration-cache compatible.

Not performed: real Tunnel run/login/create/route, Cloudflare managed-transform/analytics/proxy changes, DNSSEC enablement, public-origin/firewall probing, certificate issuance, or any remote staging test in this phase. Those require the owner's separate deployment step and acceptance checklist.
