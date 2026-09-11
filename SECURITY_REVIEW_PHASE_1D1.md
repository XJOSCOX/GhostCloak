# Phase 1D.1 security review — origin hiding preparation

Ghost Cloak is not anonymous. Cloudflare Tunnel is not Ghost Mode. This phase contains deployment design, templates, an IP-use audit and local tests only. No VPS login, public-origin probe, production certificate issuance, Cloudflare configuration, DNS cutover, proxy enabling or live firewall change occurred. The owner's supplied successful Phase 1C.2 staging results are accepted as context, not represented as newly measured facts.

## Scope and trust boundary

Current staging connects clients directly to public nginx. The prepared target connects them to Cloudflare edge, then an authenticated outbound cloudflared tunnel, a private TLS Unix socket, nginx, loopback backend and loopback PostgreSQL. Cloudflare sees source IP and TLS-terminated requests, including timing, hostname/path/sizes, bearer credentials and public protocol metadata. It does not acquire libsignal plaintext/private keys from this design, but it can disrupt availability, steal active tokens or manipulate first-contact directory responses. Existing pins and out-of-band safety numbers remain required.

After proper cutover, the API application has no need for visitor socket/header IP, and the VPS no longer receives direct visitor API TCP connections. Cloudflare/hosting/root can still observe or instrument traffic; origin nginx/cloudflared can transiently receive supplied address headers before filtering. Application users can put IP-shaped strings in ordinary fields. These limits preclude an absolute claim that no IP string can ever reach any process. The goal is removal of network-address input to supported business logic/persistence, not censorship of arbitrary content or protection from a compromised host.

## Origin authentication and closure

The private nginx template has no TCP bind; a root-owned 0750 parent restricts its Unix socket to the connector group. TLS authenticates nginx with the existing publicly trusted hostname certificate; noTLSVerify remains false. The dedicated nginx worker UID is the only process UID permitted by the prepared OUTPUT nft rule to connect to backend 127.0.0.1:8787. That rule is essential: loopback alone would not prevent arbitrary local processes forging the HTTPS marker. No custom cryptographic protocol, secret request header, shared DNS token or new client auth scheme was introduced.

Cloudflared runs non-root with no capabilities, restricted filesystem/address families/resources, null logs and a tunnel-scoped credential file. nginx's master requires a limited root capability set to load protected keys and drop worker identity; workers have no interactive shell. Root/CAP_NET_ADMIN, compromised intended services and stolen tunnel credentials remain capable of bypass or impersonation. Do not share service UIDs or group membership with unrelated workloads. Guard health requires operational verification after firewall reloads; systemd active state does not prove nft rules have not been flushed.

Target inbound exposure is only SSH 5683. Origin API ports 80/443/8787/5432 are closed at host/provider over IPv4/IPv6, and nginx has no public listener. Cloudflared metrics remain loopback and are not proxied. Connection failure never automatically restores direct access. Fail-closed behavior trades availability for preventing an unreviewed privacy downgrade.

## Visitor metadata and rate limits

Backend audit found no reads of socket peer IP, forwarded-IP headers or address-derived routing/authentication/rate-limit principals. Existing challenge/session/identity/routing identifiers and SQL schema are unchanged. No backend refactor or dependency change was necessary. Regression tests guard against forwarding plugins/address accessors and IP schema fields.

nginx's new request-header allowlist drops all unneeded headers, including CF-Connecting-IP, CF-Connecting-IPv6, X-Forwarded-For, True-Client-IP, Forwarded, X-Real-IP, CF-Ray/location and future arbitrary headers. It retains Authorization, content type/framing, fixed Host and fixed HTTPS marker. No real-IP module or IP logging is configured. The old direct template additionally clears the Cloudflare visitor headers, but remains a historical public-ingress template and must not be installed alongside the tunnel profile.

Cloudflare's Remove visitor IP headers transform is a separate owner action, not the sole boundary; its multi-CDN X-Forwarded-For behavior may retain a proxy IP. Edge abuse controls may use network signals within Cloudflare. Origin nginx uses an aggregate overload budget, while the backend preserves anonymous/device-derived hashed buckets and existing mailbox/prekey/session quotas. Anonymous/shared-budget exhaustion, NAT false positives, distributed abuse and volumetric attacks remain risks; no server-side IP reputation data is added.

Origin access/error/request logs are disabled. Cloudflare security/analytics/operational retention is neither zero nor controlled by nginx. Disable unnecessary exports/tracing/header enrichment and review available plan controls before cutover. Debugging must use synthetic-only requests without tokens/bundles/ciphertext logs. See LOGGING_POLICY.md and protocol/METADATA_PRIVACY.md.

## Historical origin, DNS and outages

Staging's 15.204.230.25 is historically exposed. Proxy DNS/Tunnel cannot erase passive records or scanning history. Prefer a fresh production VPS never published through A/AAAA/other public services; otherwise explicitly accept discoverability of staging. Cloudflare and providers still know the new origin. DNSSEC is recommended but not enabled; it authenticates answers rather than hiding queries or repairing historical disclosure. DS publication/rollback must be coordinated to avoid SERVFAIL.

Cloudflare outage can make normal messaging unavailable. Durable-outbox behavior and TTL bounds are unchanged. No public-origin fallback is shipped. Manual return to direct staging requires separate owner authorization, saved configuration and an explicit renewed-exposure decision. Fresh-origin migration must avoid two divergent writable databases, protect backups and revoke restored sessions under the existing restore policy.

## Local verification and limits

See infrastructure/tunnel/tests/README.md for reproducible offline Linux validation and exact results. The validator runs with no network access and no host port publications; NET_ADMIN is scoped to its disposable container namespace, not the Windows host or VPS. It tests nginx syntax/TLS Unix transport, route/header filtering, restricted socket access, backend UID bypass protection, private listeners, cloudflared offline config matching and systemd unit syntax. Synthetic TLS keys are generated only inside that temporary container; no owner credentials or real certificates are read/configured.

Ktor tests exercise spoofed visitor headers throughout Alice/Bob/Charlie registration, proof verification, lookup, encrypted send/dedupe, fetch, cross-user ACK denial, duplicate ACK, unauthorized fetch and minimal health. Logging/principal captures exclude supplied test addresses. The same probe runs against PostgreSQL and scans every application table for the test strings and UTF-8 hex. Existing encryption/auth/mailbox/crash tests and strict dependency checks are retained.

Static guards are regression checks, not a proof against every future reflective/native/logging mechanism. Local unit parsing is not target-host systemd sandbox execution. No real tunnel connection, managed transform, DNSSEC, public-CA/SSH/firewall cutover or outage drill was performed here. Owner deployment must verify those controls before claiming the origin closed. Existing endpoint crash gaps, lack of rollback detection and other Phase 1C.2 limitations remain unchanged.

No Phase 1D.2, Ghost Mode, P2P, Tor/VPN, OHTTP, mixnet or unrelated messaging feature was implemented. Independent review and the owner's later deployment approval remain gates; this preparation ends here.
