# Security review — Phase 1D.3

Date: 2026-09-11. Base: bbdb175. Scope: client transport extraction, local tests and architecture documentation. This is a development review, not an independent security audit.

## Result and scope

The app composes the existing `HttpGhostClient` with the default direct HTTPS transport through `TransportPolicy`. Auth, directory and mailbox API shapes remain unchanged. No cryptographic format, backend schema, server authorization, dependencies or verification pins change. Relay code is absent; selecting Private relay without both explicit gate enablement and an adapter fails closed. An injected adapter's failure propagates with no direct retry path. Android has no new user-facing mode, permissions, services or background behavior.

The owner reports Phase 1D.1/1D.2 staging deployment and tests passed. This phase neither independently validates those live claims nor performs deployment, SSH, Cloudflare account actions, live API requests, VPS/DNS/firewall edits or certificate issuance. Test certificates are ephemeral local fixtures only.

## Findings and boundaries

| Area | Review result | Remaining limitation |
|---|---|---|
| TLS and endpoint identity | Existing platform certificate/hostname validation, endpoint validation and no redirects preserved; no new trust manager or CA bypass in production | Future adapters require independent inner endpoint verification and security review; an interface cannot enforce correct cryptographic implementation |
| Encoded requests | Protocol client encodes once; direct transport sends exact bytes; mailbox/outbox unchanged | Transport is trusted in-process code with access to bearer and encoded request; malicious adapters are outside this boundary |
| Responses | Direct implementation bounds streaming reads; client validates type, size, canonical schema, version, status and error for every adapter | Adapter must bound encapsulation before materializing response; existing timeout/error semantics remain |
| Authentication | Application bearer remains separate from any future relay credential design; no proxy identity response fields exist | Cloudflare sees reusable bearer, login IDs and token grants; relay IP substitution alone does not hide account linkage |
| Feature gate/failure | Standard default; absent/disabled relay rejected; no fallback branch | User preferences, persistence, production relay provisioning and private-mode rollout are not implemented |
| Peer metadata | No auth/message peer-address input; existing backend source/header tests retained | Logs tests are not proof against root/provider instrumentation, arbitrary user data or global correlation |
| Sender metadata | Current authenticated sender and outer device/routing fields documented | Sealed sender requires new delivery authorization, replay/deduplication and migration review; nothing implemented |
| Abuse | Existing anonymous/device principals, session/destination checks and bounded capacities unchanged | Shared anonymous budgets and relay egress rate limits can cause false positives; no new anonymous delivery credential |
| Android organization | Only composition changes in controller; transport code remains in transport module, docs separate from UI/theme | No claim that Android has a working privacy relay today |

`protocol/METADATA_PRIVACY.md` inventories current per-layer visibility and seven distinct adversaries. `protocol/RELAY_ARCHITECTURE.md` compares HTTP forwarding, CONNECT/MASQUE, OHTTP, double-hop and self-hosting, with current official provider availability references and no account-specific price assumptions. `protocol/SEALED_SENDER_DIRECTION.md` records service-visible sender, destination, IDs and timing. `Android/TRANSPORT_PRIVACY.md` records the client boundary and rollout constraints.

## Validation record

Validation is local only. Final results on 2026-09-11:

| Check | Result |
|---|---|
| `gradlew -p Android test :app:assembleDebug :app:assembleRelease --dependency-verification strict` | Passed: 55 test-support tests (including 5 new transport tests), 1 app unit test, debug/release assembly |
| `:test-support:postgresTest` with isolated loopback `ghostcloak_test` | All 8 passed, including HTTPS delivery/restart/deduplication, crash boundaries, concurrent single-use operations and visitor-header DB checks |
| `:app:connectedDebugAndroidTest :storage:connectedDebugAndroidTest` targeting only emulator-5554 / Pixel_10_Pro_XL API 37 | All 19 passed: 9 app and 10 storage/Keystore tests |
| `gradlew -p Android -I gradle/verify-ide-sources.init.gradle verifyIdeSources --no-configuration-cache --dependency-verification strict` | Passed: Gradle 9.7.1 source ZIP and 533 IDE source/Javadoc/sample artifacts; verification metadata unchanged |
| Patch scope/whitespace review | No protocol/backend/crypto/infrastructure/dependency changes; unrelated existing launcher assets and root Android build-file edit excluded from phase commit |

The first test runs exposed overly specific error expectations in the new URLConnection fixture (redirect/authentication statuses can provide no error stream and produce the existing `invalid_response`). The fixture now checks redirect rejection and a 403 response with an available error body; production error semantics were preserved. The final JVM/build rerun is green. Local TLS tests also reject both a wrong hostname and the fixture certificate under the normal trust store.

Gradle reports existing deprecations for future Gradle 10 and the Android test runner reports a protobuf/Unsafe warning; these do not fail validation. No dependency changes were made to suppress them. Builds used the current workspace, including the owner's unrelated launcher artwork; those files remain outside this phase's commit.

The live `stagingTest` task is intentionally excluded: no real service request is made in this preparation phase. The owner's reported live validation is not presented as an execution by this phase. The already-running emulator was left running; the isolated PostgreSQL process started for this validation was stopped afterward.

## Deferred work

No production relay server, MASQUE/OHTTP implementation, Privacy Pass, anonymous credentials, sealed sender, Tor, VPN, WebRTC/STUN/TURN, Ghost Mode, groups, media, calls, push changes, cover traffic or encrypted DNS. No immunity to traffic correlation or endpoint compromise is claimed. Existing Phase 1C.2 endpoint crash/persistence gaps remain. Do not begin a later phase as part of this change.
