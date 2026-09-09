# Phase 1A security self-review

This is a developer self-review, not an independent security audit. Test results are evidence of specific behavior, not a proof of security.

## Completed protections

Independent randomly identified endpoints; library-generated identity keys; signed prekey establishment; evolving Signal message and asymmetric ratchets; ciphertext-only mock and backend boundaries. Persistent TOFU pins reject replacement. Signal fingerprints are symmetric across the relationship. CBOR version/size/framing checks and encrypted routing bindings reject altered routing data. Signal counters reject duplicate messages and support bounded out-of-order delivery. Failed authentication or transaction commit does not return accepted plaintext. Session destruction removes session records and blocks resurrection.

Room stores all records inside SQLCipher under a randomly generated database secret wrapped by Android Keystore. Backups are disabled and endpoint files use noBackupFilesDir. Missing wrapping keys and modified wrapping ciphertext fail closed. No plaintext message table, analytics SDK, crash SDK, phone identity or hardware identifier collection exists. Application logging accepts fixed enum codes only; SQLCipher Java logging is disabled and libsignal logging is not initialized.

## Known limitations

TOFU cannot authenticate the first person behind a key. Device routing IDs are prototype contact identities; a future username/contact mapping must prevent silent rebinding to a new device ID. Per-endpoint serialization assumes a single engine; no multiprocess use. Persistent storage has no rollback-resistant monotonic counter. Native/JVM memory wiping and flash erasure are not guaranteed. No delivery outbox or exactly-once application delivery exists: a crash after commit but before the caller receives the result can lose a message result. Synthetic demo data must never be replaced with real private conversation data in test output.

CBOR framing is pinned to one encoder profile, without cross-language conformance vectors or coverage-guided fuzzing. Identity event streams are bounded; callers must handle the IdentityChanged failure even if the live event was not collected. SQLCipher native diagnostics and upstream binary internals require independent logging review. Prekey pool is capped at 32 issued bundles, with no automatic retirement or last-resort Kyber handling. Exhaustion stops rather than replacing the protocol.

## Cryptographic assumptions

Trust Signal's implementation of signed authenticated session establishment, message authentication and ratchet evolution, platform entropy, Android Keystore, SQLCipher and dependency delivery. The progression test inspects public message counters and public ratchet keys only; it does not prove forward secrecy, post-compromise security or destruction of old secret bytes. Identity keys never directly encrypt application bodies.

## Metadata currently visible

The local mock/backend sees random sender/recipient routing IDs, envelope IDs, protocol version, message type, packet length and public directory material when supplied. Future network operators would see IPs, timing and traffic sizes. No metadata anonymity or traffic padding is provided.

## Endpoint risks

Unlocked endpoint compromise can recover plaintext and use database keys. Keystore keys are not user-authentication gated. Hardware backing is reported only when KeyInfo says it exists. Emulator testing is not hardware-backed-device validation. Clipboard, screenshots, accessibility services and screen sharing can disclose future displayed plaintext. JVM copies, JNI/native handles and live database connections retain sensitive material.

## Server compromise consequences

The backend has no endpoint private keys, session keys or decryption interface. Server-side material alone cannot decrypt captured E2EE payloads under the protocol assumptions. Compromise can deny service, collect metadata, replay packets, steal future auth credentials or substitute initial directory keys. It can attempt identity changes, which already pinned clients reject. No administrator/recovery decryption path exists.

## Unimplemented protections

Production networking/TLS configuration, account authentication, directory key transparency, verification UX/QR codes, rate limiting, prekey rotation/replenishment, rollback resistance, deletion assurance, recovery, backup and independent audit. Ghost Mode, groups, media, calls, multi-device and all unrelated features remain absent.

## Dependencies requiring review

libsignal 0.102.1: unsupported third-party use, unstable API, AGPL-3.0 obligations, JNI binaries and Java 21/desugaring requirements. sqlcipher-android 4.19.0: BSD-style and bundled licenses, native logging, ABI/page-size support. Room 2.8.4, CBOR serialization 1.9.0, coroutines 1.10.2 and build tooling also need supply-chain review. Direct versions are pinned; artifact provenance and a release SBOM/signature policy remain open. Do not automatically upgrade crypto to silence dependency-age warnings.

## Items requiring independent security audit

Identity binding, contact rebinding, library store callback semantics, failure atomicity and crash boundaries, replay across restoration, canonical parsing/fuzzing, malicious signed bundle handling, native packaging/logging, Keystore fallback/invalidation, SQLCipher/WAL key lifetime and platform backup exclusion. Test additional supported Android versions and real hardware.

## Phase 1B blockers

Resolve licensing/distribution requirements and arrange independent review before security claims. Design authenticated username-to-device binding, key verification/change acceptance, prekey replenishment and retirement, authenticated directory allocation, storage rollback policy, transport authentication/TLS, metadata retention and crash-safe delivery. No production deployment or next-phase feature implementation should begin automatically.

## Verification

JVM acceptance/attack suite: 11 tests passed. Android storage instrumentation: 5 tests passed on the API 37 emulator, including database reopen/replay, transaction rollback, missing Keystore key, modified wrapped secret and missing database. The existing app unit and instrumentation checks also passed (18 tests total). Debug and unsigned release APKs assembled. Android lint: zero errors and 19 dependency-age/starter-resource warnings. APK inspection confirms desktop native binaries and libsignal's testing JNI library are excluded; normal Android libsignal and SQLCipher JNI libraries remain. Manual emulator smoke confirmed the Compose screen, persistent random local device ID and SOFTWARE Keystore reporting. The initial cold launch exceeded adb's wait timeout before the screen became available; startup performance is not characterized. These results do not validate real hardware or other Android versions.
