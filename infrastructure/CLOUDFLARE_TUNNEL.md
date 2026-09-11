# Cloudflare Tunnel preparation — Phase 1D.1

**Design and local validation only. Do not execute the deployment steps without the owner's later deployment authorization.** No live VPS/Cloudflare configuration or certificate issuance occurred. Owner-reported staging facts: public trusted nginx TLS at api.ghostcloak.org; backend 127.0.0.1:8787; PostgreSQL 127.0.0.1:5432; inbound 443 and SSH 5683; working DNS-01 renewal, restricted DB role, non-root backend, Alice/Bob/Charlie flow and plaintext-free dump. These are supplied staging results, not newly probed here.

## Chosen local boundary

Use cloudflared → **TLS over Unix socket** → nginx → existing loopback backend. Cloudflare documents `unix+tls:/path` support in its [protocol table](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/routing-to-tunnel/protocols/). This is option A's TLS boundary with filesystem-local transport instead of TCP 443. It retains nginx limits/header filtering and lets the OS restrict which service can connect.

Compared with HTTPS on 127.0.0.1:443, this avoids an additional listening port and lets a root-owned 0750 parent directory admit only the connector group. Plain HTTP directly to 8787 would skip nginx filtering and allow local callers to assert the existing HTTPS marker; it is not selected. HTTP over Unix socket would offer the same filesystem gate with less TLS maintenance, but the existing working DNS-01 certificate makes TLS verification practical.

The existing Let's Encrypt certificate can remain internal. Confirm its real file paths before using the template. `originServerName: api.ghostcloak.org` supplies certificate/SNI identity despite socket transport; `noTLSVerify: false` remains mandatory. The operating-system CA store validates the certificate. A Cloudflare Origin CA certificate is an alternative only after deliberate CA trust configuration in cloudflared, with separate renewal/replacement policy; it is not inherently necessary or currently configured. No new CA or certificate is issued here. See [origin parameters](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/origin-parameters/).

## Local impersonation controls

Three controls form the prepared boundary:

1. nginx has **only** `/run/ghostcloak-tunnel/origin.sock`, under root:ghostcloak-tunnel 0750. Only the non-login cloudflared service gets that supplementary group. The parent is not writable by the connector, so it cannot substitute the origin socket or key. nginx's root master reads TLS material, then workers run as dedicated `ghostcloak-origin`.
2. `origin-guard.nft` restricts TCP connections to 127.0.0.1:8787 to the dedicated nginx worker UID. Without this rule an arbitrary local process could bypass socket permissions and forge X-Forwarded-Proto directly. The guard is required, not optional. Do not reuse a shared www-data UID. Backend and PostgreSQL bindings remain unchanged.
3. Cloudflared's tunnel credential authenticates its outbound connection to Cloudflare. DNS, hostname ingress and fallback 404 constrain the published application. A visitor-supplied Cloudflare header is never proof of origin or user authentication.

The local guard compares process UID, not visitor IP, and has no connection logging. It does not protect against root, CAP_NET_ADMIN, a compromised nginx/cloudflared service, credential theft or kernel compromise. The host is dedicated/trusted. Audit group membership, UID isolation, socket permissions and the nft chain after every reboot/firewall reload. The oneshot guard unit deliberately never removes the rule on stop; service health alone does not detect an administrator flushing nft rules.

## Files and service setup for owner review

`tunnel/` contains a complete private nginx configuration, locally managed tunnel YAML, cloudflared/nginx/guard systemd units, tmpfiles directory rule, nft rule and isolated Linux validator. This is a replacement for the public nginx listener, not an extra vhost layered onto it.

The later operator should create non-login system users `cloudflared` and `ghostcloak-origin`, plus group `ghostcloak-tunnel`; grant only cloudflared membership in that connector group. Install binaries root-owned and non-writable by services. Install:

| Source | Destination |
|---|---|
| cloudflared.yml.template | /etc/cloudflared/ghostcloak.yml |
| nginx-origin.conf.template | /etc/ghostcloak-origin/nginx.conf |
| origin-guard.nft | /etc/ghostcloak-origin/origin-guard.nft |
| *.service | /etc/systemd/system/ |
| ghostcloak.service.d/10-tunnel-origin.conf | /etc/systemd/system/ghostcloak.service.d/10-tunnel-origin.conf |
| ghostcloak-tunnel.tmpfiles.conf | /etc/tmpfiles.d/ghostcloak-tunnel.conf |

Use /etc/cloudflared root:cloudflared 0750, config root:cloudflared 0640 and the tunnel credential cloudflared:cloudflared 0600 (read-only under the service sandbox). Keep nginx config/root-only certificate private keys outside Git. Backend runs under the existing ghostcloak user. The origin master needs limited UID/GID/key-read capabilities; cloudflared has an empty capability set, read-only filesystem, null logs, resource limits and no login shell. Metrics bind 127.0.0.1:20241 only and are not forwarded.

Use a **locally managed named tunnel**, so reviewed ingress is in the YAML. From a separate trusted administration environment the owner can later create it, copy only its tunnel-scoped credential to the VPS and substitute its UUID in the local YAML. Do not put an account-wide `cert.pem` on the service host. Do not put tokens in ExecStart, environment dumps, shell history or support bundles. Tunnel credentials differ from the Certbot DNS-edit API token: never reuse that token. Cloudflare documents the [credential scopes](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/local-management/tunnel-permissions/). Revoke/replace a compromised tunnel credential through the management account and repeat validation; it should not be treated as an expiring short-lived token.

The prepared HTTP/2 connector uses outbound TCP 7844, plus DNS and ordinary operator-managed update/CA/renewal needs. No inbound Cloudflare port/range allowlist is needed. If later selecting QUIC, explicitly review outbound UDP 7844. Do not hardcode today's edge IP ranges into application logic; consult the maintained [firewall destinations](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/tunnel-with-firewall/) before egress restrictions. There is no WARP/private-network route or SSH tunnel in this design.

Before deployment, the operator must run `nginx -t`, `systemd-analyze verify` for all units/drop-ins, `nft -c -f` for the guard, and `cloudflared --config /etc/cloudflared/ghostcloak.yml tunnel ingress validate`. Review existing nft/UFW management before loading the dedicated table; never flush the global ruleset. The nft file atomically replaces only its own table and is repeatable. tmpfiles must create the protected parent before nginx starts. Confirm the guard persists across boot and firewall reload. The backend drop-in requires the guard BEFORE binding its port; private ingress starts after backend/guard, and connector after private ingress. Install the drop-in with this profile so boot does not leave an unguarded local-port window.

The public nginx service must be retired during the approved transition. Update the existing Certbot deploy hook to syntax-check and reload **ghostcloak-origin.service**, not restart the old public nginx unit. Keep DNS-01 renewal; no port 80 exception is needed. Test renewal after the future transition, without turning off origin TLS verification.

## Edge, DNS, and firewall transition (not executed)

1. Preserve the current reviewed nginx/DNS/firewall configuration privately. Verify a second SSH session on **5683** and provider recovery-console access. Do not substitute the old documentation's port 22. Keep DNS TTL/cache delay in the maintenance plan.
2. Validate the protected local socket, UID guard and credentials with public ingress still unchanged. The later operator may establish the outbound named tunnel without routing the public hostname yet. Do not run a quick tunnel or alternate public hostname as a shortcut.
3. In Cloudflare's later configuration, enable **Remove visitor IP headers**, disable adding True-Client-IP/location headers, and disable IP geolocation headers where applicable. Do not rely on this transform alone: X-Forwarded-For can retain a previous CDN hop. nginx independently drops the entire header and unknown extras. See [Managed Transforms](https://developers.cloudflare.com/rules/transform/managed-transforms/reference/).
4. Review edge abuse rules for POST API routes; bypass cache for the entire hostname, disable unnecessary analytics/Workers/Logpush, and avoid browser JavaScript challenges/Access login redirects the native CBOR client cannot solve. No new client-identifying headers. Edge security events may still retain IP metadata; verify actual plan settings/retention instead of claiming zero logs.
5. Replace the current `api.ghostcloak.org A → 15.204.230.25` (and any competing AAAA) with the tunnel's **proxied** CNAME target `<TUNNEL-UUID>.cfargotunnel.com`, using Cloudflare's published-hostname/DNS workflow. Confirm there are no competing A/AAAA or wildcard routes. Do not execute this step during preparation.
6. Validate public HTTPS, the unchanged hostname, minimal health and the owner-operated staging flow. Validate that supplied visitor headers are absent upstream using synthetic-only diagnostics, never real request dumps. Verify an unprivileged user cannot access the socket or backend port, while the intended connector/worker can.
7. Close public 80/443/8787/5432 at host **and provider** firewalls, retire the old public nginx listener and verify externally over both IPv4 and IPv6. Keep only SSH 5683. `ss -lntp` should show backend/DB/metrics on loopback and no nginx TCP listener. See VPS_HARDENING.md for exact staged UFW commands.

Do not declare origin closure until outside probes reject direct requests to the old address (including correct Host/SNI), not just until DNS returns Cloudflare. Cached historical clients may fail after closure; that is preferable to silently retaining a bypass.

## Health, outages, rollback, DNSSEC

Public `/health` remains only `{"status":"ok"}` or unavailable; no versions, counts, internal addresses or uptime. It is the only GET proxied. cloudflared metrics/ready diagnostics stay private and are not aliases of public health. Monitor coarse availability/connector state and certificate expiry without copying raw metrics/debug streams into public endpoints.

Cloudflare/Tunnel outages fail closed. No watchdog, script, DNS failover or application path reopens the origin. Preferred rollback restores a previously validated private config/connector while DNS stays tunneled. If the owner explicitly chooses a return to Phase 1C.2 direct staging, that is a separate privacy downgrade: restore the saved public nginx configuration, deliberately reopen 443 in both firewalls, verify TLS, then restore the saved A/AAAA DNS design after removing the tunnel CNAME. Preserve SSH 5683; do not auto-remove the guard (a reverted worker UID may need an explicitly reviewed change). Document the renewed IP exposure and DNS cache delay. An outage alone is not authorization to take that downgrade.

Recommend DNSSEC for ghostcloak.org after owner review. With Cloudflare Registrar, enable signing in Cloudflare DNS and verify the automatic parent DS publication. With another registrar, publish the exact provided DS there and check it matches the current DNSKEY. Use `dig +dnssec ghostcloak.org DS`, DNSKEY queries and a validating resolver/`delv` to check the chain and absence of SERVFAIL. DNSSEC changes must not leave stale parent DS records during rollback or provider changes. Follow the [DNSSEC workflow](https://developers.cloudflare.com/dns/dnssec/); nothing is enabled here.
