# Phase 1C.1 security review

Local prototype only. This implementation has not undergone independent security review and is not production-ready. Phase 1C.2 and deployment have not begun.

## Implemented protections

Separate platform P-256 device credential, registration possession proof bound to all public registration fields, service/account/device/purpose-bound random challenges, one-attempt challenge consumption, five-minute opaque sessions stored hashed server-side, revoke/expiry, immutable credential registration, conservative normalized usernames and authenticated exact lookup. Existing Signal trust/fingerprint checks stay at endpoints. No password, phone, email, OAuth identity or hardware fingerprint.

Directory consumes complete existing libsignal public bundles atomically. Ownership, pool/count/size/ID/identity checks and immutable signed-ID material prevent cross-device uploads and accidental one-time ID reuse. No homemade key agreement. Backend structural checks do not replace libsignal signature validation on clients. Malicious public material can still deny service.

Mailbox validates bounded outer envelopes and authenticated routing, stores ciphertext only, restricts fetch and ACK to the session owner, uses explicit idempotent ACK and sender-scoped idempotent submission, and limits queue/dedupe retention and capacity. Backend depends on protocol only (plus serialization), never endpoint crypto/storage. In-memory repository dump tests include all tables and search for fixture plaintext, raw tokens and private endpoint records.

Durable outbox has conservative ambiguous-encryption failure and exact-ciphertext retry, with all four requested crash-boundary tests. No crypto rollback or blind re-encryption. Network API is behind transport/auth/directory interfaces. Existing local simulator and UI remain intact. New Android INTERNET permission enables HTTPS adapters; global cleartext=false and backup restrictions remain intact.

## Evidence and limits

NetworkTest uses a real loopback HTTP server/client and native libsignal: Alice/Bob/Charlie registration, login, exact directory lookup, encrypted upload while Bob is offline, authenticated fetch, local decryption, endpoint replay rejection and ACK. Attack tests cover expired/replayed/altered/wrong-binding challenges, wrong signatures, revoked/expired/malformed tokens, registration possession and uniqueness, malformed/reserved usernames, key/size/duplicate-prekey rejection, ownership, atomic concurrent prekey consumption, envelope/route/size validation, dedupe conflict, TTL, unauthenticated mailbox access, cross-owner ACK, oversized/content-type/schema/version failures, cleartext restrictions and fixed-category logs/rate limiting. SQLCipher instrumentation tests exercise auth signing/token/outbox state across reopen. Existing Phase 1A/1B suites remain acceptance gates.

This is not a proof of protocol security, anonymity, timing resistance, memory erasure, DoS resistance or production database correctness. Exact generated checksums are not a substitute for independent dependency provenance review. Crypto dependencies remain pinned. No verification exception or new dependency is required for this phase. The IDE source/Javadoc/sample/distribution check remains enabled; full proprietary Android Studio sync is distinct from running that check.

## Validation result (2026-09-09)

65 tests passed: 47 JVM test-support tests (including nine network/security/crash tests), one app unit test, eight app instrumentation tests and nine storage instrumentation tests. Instrumentation ran only on the isolated API 37 emulator; the connected physical device was not targeted. Debug and unsigned release APKs assembled. Lint: zero errors, 23 existing dependency-age/starter-resource warnings. Strict IDE verification passed for 427 source/Javadoc/sample JARs and the Gradle source ZIP. No verification metadata or dependency pin changed. Android Studio full sync was not driven by this task.

## Server compromise and metadata

A database dump has public account/device/key/directory data, live challenges, session hashes, opaque queued ciphertext and routing/timing/dedupe metadata. No message plaintext or endpoint private/session/database keys are uploaded. A hostile server still observes source IP at its transport, username lookups and communication relationships, can substitute first-contact keys, reorder/replay/drop traffic, lie about acceptance/ACK, retain deleted ciphertext and deny service. Safety-number verification and existing identity pins are essential. See SERVER_METADATA.md for every endpoint; no claim that TLS hides source IP.

## Authentication assumptions

JCA random generation/curve/signature implementations and platform TLS trust are assumed sound. Auth private key is exportable inside an encrypted endpoint DB; it is not claimed hardware-isolated. Endpoint compromise defeats endpoint secrecy. Credential loss has no recovery/rotation endpoint; future rotation requires old and new proof plus revocation. One active session per device and no multi-device support. Backend clock changes affect TTL. Token theft permits mailbox control until revoke/expiry. Hash-map lookup of a random-token hash is not a constant-time HTTP service guarantee.

## Retention, abuse and crash consistency

Default mailbox TTL 24 hours, dedupe seven days, no archives. Cleanup runs on traffic or explicit invocation; idle persistent cleanup/backup erasure is future work. Storage is bounded, but the snapshotting/scan-based memory adapter is not a durable PostgreSQL implementation. Anonymous bucket exhaustion, Sybil accounts, username enumeration, destructive prekey lookup, mailbox filling and metadata leakage remain. No plaintext moderation or invasive rate-limit fingerprinting.

Outbox retries are byte-identical, but the encryption-marker window may lose one unsent logical message rather than re-encrypt. Inbound decrypt/history commit is not atomic; a future production network UI needs durable acceptance receipts before ACK-on-replay. No exactly-once delivery claim. Local server restart loses in-memory account/mailbox/dedupe state; this is an explicit development limitation.

## Phase 1C.2 blockers

Independent protocol/auth/parser review; robust production HTTP framework/ingress selection and request deadlines/aggregate limits; PostgreSQL schema/transactions/unique constraints and concurrency tests; persistent retention scheduler, encrypted backups and erasure policy; authentication key lifecycle/recovery design; durable inbound receipts and app network onboarding; operation of durable outbox as a single owner; multi-host clock/failure testing; production abuse controls; TLS/service configuration and secure secret provisioning; legal/dependency release review. None is deployed by this change.

## Phase 1D privacy blockers

IP visibility, lookup enumeration, sender/recipient linkage, timing/size correlation, proxy logging, relay trust/collusion and traffic analysis are unsolved. No Cloudflare, VPS, relay, Tor/VPN, Ghost Mode, WebRTC, P2P, push, media, groups or analytics are implemented.
