# Android transport privacy — Phase 1D.3

Android keeps the current Standard connection path. `NetworkController` explicitly selects `TransportPolicy.select()`; the default is `DirectHttpsTransport`. There is no new settings screen, privacy claim, relay endpoint, provider SDK, permission, VPN service, persistent notification or background tunnel. Existing screens and separate UI/theme files are untouched by this phase.

## Boundaries

| Layer | Responsibility | Data crossing boundary |
|---|---|---|
| App runtime/controller | Existing endpoint/account lifecycle and outbox operations; composition chooses transport | Configured API origin and endpoint state |
| `NetworkAccount` / `NetworkMailboxTransport` | Existing challenge/session, directory, send/fetch/ACK semantics | Protocol objects and opaque encrypted envelopes; no peer IP |
| `HttpGhostClient` | Validate origin, encode CBOR, attach application bearer, validate response | Intended endpoint URI, exact body, optional app Authorization |
| `GhostCloakTransport` | Connection/encapsulation contract | Only status, content type, bounded body back to protocol client; no identity headers |
| `DirectHttpsTransport` | Current platform TLS/hostname verification, bounded POST I/O and disabled redirects | Direct HTTPS to configured application endpoint |
| Future relay adapter | Separately provisioned relay authentication and independently verified application endpoint | Outer relay credentials must stay separate from inner application bearer |

The API origin and challenge audience remain the application hostname. Production Android origin validation and platform CA/hostname verification remain in force. The explicit loopback HTTP exception is for local JVM fixtures; Android never enables it. A transport adapter is trusted executable code; tests and a separate security review are required before enabling a real implementation.

`TransportPolicy.select(PRIVATE_RELAY, ...)` rejects a disabled gate or absent adapter with `relay_unavailable`. Tests can enable it with an in-process synthetic adapter. There is no production relay adapter or remotely enabled flag. Relay execution failures propagate to existing caller/outbox handling; policy contains no direct-fallback catch/retry. There is no Ghost Mode enum/UI or fallback preference yet. Future rollout must persist user mode across retries/restarts and require explicit informed opt-in for any less-private fallback. Standard remains the only shipped selection.

The low-level contract is suspendable; `HttpGhostClient` dispatches execution to `Dispatchers.IO` as before. A future adapter must apply bounded reads before allocating a whole response and preserve cancellation/lifecycle behavior. Neither a VPN permission nor a global device proxy is necessary merely to substitute an app-owned transport. DNS, private-relay bootstrap, remote resolution, mobile battery/bandwidth and provider choices remain design work; see [relay architecture](../protocol/RELAY_ARCHITECTURE.md).

## Verification

`TransportPrivacyTest` exercises actual local HTTPS, hostname mismatch, direct POST/body/bearer behavior, unauthenticated requests, response limits, redirects and forged response headers. A socket-free synthetic relay runs registration/login plus Alice/Bob/Charlie encrypted send/retry/fetch/ACK and checks exact envelope bytes. Gate/outage tests and static peer-address dependency checks complement existing network, origin-header, database and Android suites. These are local tests, not proof of a future relay's privacy or a live Cloudflare test. No dependency or verification-metadata change is needed.
