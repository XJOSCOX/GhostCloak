# Metadata privacy — Phase 1D.3

Ghost Cloak is not anonymous. The owner reports successful Phase 1D.1/1D.2 staging deployment and Alice/Bob/Charlie plus synthetic visitor-IP log tests. This phase accepts that report as context; it does not independently retest or modify live infrastructure.

Reported path: client → Cloudflare TLS edge → outbound cloudflared Tunnel → private nginx Unix TLS socket → backend 127.0.0.1:8787 → PostgreSQL 127.0.0.1:5432. Only SSH 5683 is publicly inbound; the UID guard restricts backend connections to ghostcloak-origin. Visitor headers are removed where supported and nginx uses an upstream header allowlist. These controls keep visitor IP out of supported application business logic; they do not blind Cloudflare or a compromised root operator.

## Current metadata inventory

“Required” means required by today's protocol, not inherently necessary forever. Sensitivity and reducibility are independent of that requirement. All client API operations are POST; health is a separate minimal GET.

| Field | ISP/mobile network | Cloudflare TLS edge | cloudflared/private nginx | Backend / database | Classification and future direction |
|---|---|---|---|---|---|
| Client IP | Sees subscriber/source address | Sees network peer directly | Visitor headers may transiently arrive if removal fails; dropped before backend | Not used as identity, quota or stored client-IP field | Privacy-sensitive; removable from service-facing connection via independent relay, not from first hop |
| Hostname | DNS/SNI/address inference depending on platform/network | Exact API hostname | Exact API Host/route | Endpoint/audience configuration | Required routing; privacy-sensitive, reducible local DNS/SNI exposure through future relay |
| Path | Hidden by TLS | Exact auth/directory/send/fetch/ACK path | Exact path | Exact operation; not a request access-log stream | Required today; privacy-sensitive, reducible through future encapsulation; remains visible at decapsulator |
| Method | Hidden by TLS | POST (or health GET) | Same | Same operation | Required HTTP; less variable but still privacy-sensitive |
| Body size | Encrypted record/flow sizes approximate content length | Exact request/response lengths | Exact lengths | Bounded encoded lengths and stored ciphertext lengths | Privacy-sensitive; reducible later via reviewed size padding |
| Timing | Connection/packet times | Per-request times | Per-request times | Send receipt/expiry persisted; fetch/ACK observable at execution | Privacy-sensitive; reducible later by batching/timing changes, never fully hidden |
| Authorization | TLS-hidden | Reusable application bearer, login/token responses | Bearer forwarded to backend | Resolves bearer hash to device/session; raw token not stored in DB | Required authenticated operations today; privacy-sensitive correlation handle; remove sender token only with future authorization redesign |
| Routing identifiers | TLS-hidden | Clear CBOR recipient routing, directory mapping, message IDs | Same encoded data | Routing/mailbox/deduplication relationships | Required delivery today; privacy-sensitive; future scoped capabilities/rotation need design |
| Device/account identifiers | TLS-hidden | Registration/login/directory IDs, public keys and outer envelope IDs | Same | Account/device/username/public-key/session relationships | Required today; privacy-sensitive, reducible exposure per operation in future |
| Visitor/geo/proxy identity headers | Not application-visible through TLS | Edge may generate/observe them | Removed where supported, independently stripped upstream | Not trusted or retained from network headers | Removable and privacy-sensitive; never auth/routing inputs |
| Message plaintext | Hidden by E2EE and TLS | Not available from honest endpoint ciphertext | Not available from ciphertext | Not available from ciphertext | Endpoint-only under existing crypto assumptions; compromised endpoints defeat this |

Cloudflare terminates public application TLS, so message E2EE does not hide CBOR metadata, bearer tokens, public directory bundles or routing envelopes from it. It can correlate every use of a token and associate token issuance with account/device IDs. Rotating sessions leaves login/directory/traffic links. Compromise permits active bearer theft and first-contact directory manipulation; existing identity pins and safety-number verification remain essential.

## Adversaries and limits

| Observer/adversary | Current knowledge or power | Effect of a future correctly layered independent relay |
|---|---|---|
| Backend operator | Accounts, authenticated senders, destinations, envelope IDs, ciphertext sizes, delivery/fetch/ACK timing; can instrument business requests | Source remains relay/edge separated, but application identifiers still link activity; sealed-sender direction is separate work |
| VPS provider/root | Host process memory, DB, local decrypted HTTP, tunnel timing/volume, management connections; can change code and logging | Does not automatically get original client socket IP, but can join application data with outside observations; root is not constrained by service log policy |
| Cloudflare | Client IP, API hostname, paths, bodies except E2EE payload, bearer/account linkage and timings; availability and active TLS intermediary power | CONNECT gives relay egress IP instead; HTTP contents still visible at API TLS termination. Collusion or same operator on both roles weakens separation |
| Relay operator | Absent today | First hop knows real IP; single-hop knows destination. CONNECT inner TLS/OHTTP can hide HTTP contents from relay; terminating forwarder cannot. Logs, billing, collusion and timing still matter |
| ISP/mobile carrier | Subscriber/IP, DNS depending on resolver, network destinations and sizes/timing | Sees relay endpoint/bootstrap and traffic pattern; remote destination resolution may reduce explicit API disclosure, not inference |
| Global passive observer | Can correlate endpoint and service flows by timing/volume | Low-latency relays do not guarantee protection; even multiple hops can be correlated |
| Compromised client | Plaintext, contacts, local identifiers, tokens and selected transport; can exfiltrate before encryption | Relay cannot repair endpoint compromise or guarantee truthful mode selection |

Supported source/header tests demonstrate non-use and non-retention of supplied visitor headers in supported paths, not absence of every possible IP-shaped user string or compromised-host logging. No direct peer networking is added; correspondents do not gain peer IP from this phase.

The previously public staging origin 15.204.230.25 can remain in passive DNS and scans. Tunnel does not erase historical discovery. See [origin rotation](../infrastructure/ORIGIN_ROTATION.md); no origin move occurs here. Reported edge cache bypass, disabled geolocation/NEL/Bot Fight Mode/Browser Integrity Check, retained DDoS/WAF and one unauthenticated endpoint rate rule are owner-supplied configuration facts, not anonymity guarantees.

See [relay candidates and failure/DNS policy](RELAY_ARCHITECTURE.md), [sender metadata direction](SEALED_SENDER_DIRECTION.md), and [Android boundary](../Android/TRANSPORT_PRIVACY.md). No production relay, Ghost Mode, VPN, Tor, P2P or traffic shaping is implemented.
