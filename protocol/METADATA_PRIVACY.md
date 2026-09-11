# Metadata privacy — Phase 1D.1 preparation

Ghost Cloak is not anonymous. Phase 1D.1 prepares origin hiding, not Ghost Mode or protection from traffic analysis. The owner reports working Phase 1C.2 staging at api.ghostcloak.org; no live deployment, DNS, proxy, account or firewall changes are part of this phase.

Current: client → public VPS nginx → loopback backend → loopback PostgreSQL.

Prepared target: client → Cloudflare edge → encrypted outbound Tunnel connection → cloudflared → TLS over private Unix socket → nginx → loopback backend → loopback PostgreSQL. No public inbound API port is needed. SSH remains on 5683. Unix socket ingress is stricter than a loopback TCP listener: it has no IP listener at all.

| Observer | Current staging | Target after correct deployment |
|---|---|---|
| Cloudflare | DNS/Certbot role as configured by owner | Sees client source IP, hostname, TLS/HTTP metadata, paths, time, sizes, auth headers, public bundles and opaque ciphertext at TLS termination |
| VPS kernel/provider | Direct client API connections expose client source IP | API socket peer is Cloudflare's connector transport, not the client; still sees tunnel endpoint traffic, timing/volume and management connections |
| cloudflared/nginx | nginx sees direct socket IP | Connector terminates edge transport; nginx uses Unix socket; visitor headers can still exist transiently if edge removal fails or a client includes them, then nginx drops them upstream |
| Ghost Cloak application | Does not use/log/persist socket or forwarded IP metadata | Receives only allowlisted headers; no visitor IP input to auth, routing, business limits or DB records |
| PostgreSQL | Account/device/public-key/routing/timing/token-hash/ciphertext metadata | Same schema and metadata; no network-client-IP field or IP reputation database |
| Correspondents | Existing encrypted protocol identifiers | Same identifiers and trust model; no direct peer networking is added |

Cloudflare is a trusted intermediary for availability and TLS termination, not a blind relay. It can associate a request's IP with bearer authentication and account/routing activity. Libsignal message plaintext remains endpoint-only. Cloudflare compromise can steal active bearer tokens and manipulate first-contact public directory responses; existing identity pins and safety-number verification remain essential.

The Remove visitor IP headers managed transform is defense in depth. nginx independently disables automatic request-header forwarding and supplies only Host, Content-Type, Authorization, framing and a constant X-Forwarded-Proto. It also explicitly clears known visitor address headers. No real-IP or Ktor forwarding plugin is enabled. Spoofing CF-Connecting-IP or X-Forwarded-For never selects an account, routing destination, quota bucket or trusted session.

This is not a claim that arbitrary data can never contain an IP-shaped string. A malicious client can type an address into user-controlled data; nginx must transiently parse headers, and Cloudflare/provider/root can observe or instrument traffic. Tests establish non-use/non-retention of supplied network headers in the supported paths, not absence of all possible side channels or compromised-host logging.

Origin secrecy and visitor privacy differ. The staging address 15.204.230.25 was publicly used and may remain in passive DNS, caches and scans. Tunnel activation does not erase that history. Prefer a new production VPS/IP that is never published as an A/AAAA record, or explicitly accept historical discovery of staging. Providers, administrators and Cloudflare still know the new origin. See [origin rotation](../infrastructure/ORIGIN_ROTATION.md).

Edge abuse controls may use IP/network signals inside Cloudflare. Application limits retain existing anonymous or device-derived principals, expiring hash buckets and mailbox/prekey/session quotas. Origin nginx uses a shared overload budget, not the connector's IP as a substitute for visitors. NAT/shared budgets and distributed abuse remain availability risks.

Cloudflare/Tunnel outage means the API may be unavailable. Clients keep the existing bounded durable-outbox/retry behavior; nothing automatically reopens a public origin. Long outages can exceed existing retry/TTL windows. DNSSEC authenticates DNS answers for validating resolvers but hides neither queries nor addresses. None of this implements anonymity, P2P, Tor, VPN, mixnets or Phase 1D.2.
