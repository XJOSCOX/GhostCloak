# Phase 1A.1 security self-review

This is a developer self-review, not an independent audit. Tests establish specific behavior, not proof of security. Phase 1B has not begun.

## Completed protections (implemented)

The existing Kotlin/libsignal/SQLCipher/Keystore architecture remains intact. Persistent identity trust is now explicitly UNVERIFIED, VERIFIED or CHANGED. Only an explicit expected-fingerprint approval can replace a pin; approval does not inherit VERIFIED status. A previously verified replacement raises a high-priority event. The persisted status survives loss of a live event and database reopen. Username changes do not change identity keys or safety numbers. See [trust model](protocol/TRUST_MODEL.md).

Session destruction persists a lifecycle state. Old packets cannot automatically clear it. Explicit fingerprint-validated reconnection uses new local prekey admission and libsignal authentication; both peers destroying their sessions is covered. A persistent accepted-envelope ledger complements libsignal replay counters across session replacement. Routing bindings remain encrypted and are checked before acceptance. Failed authentication, state writes or transaction commits do not return plaintext.

PreKeyManager owns local creation, replenishment, retirement and inventory. New EC/signed/Kyber IDs use separate allocations; existing IDs migrate without renumbering. Retention is an explicit maintenance policy, not a background service. Storage/programmer failures are distinguished from malformed input and protocol authentication errors. Best-effort buffer cleanup includes plaintext held across commit failure. No broad Exception catch hides unrelated failures.

The random 32-byte SQLCipher secret, AES-GCM Keystore wrapping, noBackupFilesDir, backup exclusion and disabled SQLCipher Java logging are preserved. Missing or corrupt storage fails closed without silently replacing identity. Source tests enforce the libsignal domain boundary and reject direct production logging sinks. [Logging and memory audit](protocol/LOGGING_AUDIT.md) records the limits. Gradle SHA-256 dependency verification is committed; crypto versions are unchanged.

## Verification (tested)

On 2026-09-09, all 40 available tests passed: 29 JVM test-support tests (11 existing, 16 new hardening, 2 new architecture), 1 app unit test, 9 storage instrumentation tests (5 existing extended/preserved, 4 new), and 1 app instrumentation test. Android tests ran on the API 37 emulator, explicitly selected using ANDROID_SERIAL=emulator-5554.

New coverage includes verified/unverified replacement and explicit approval; persisted verification and CHANGED state; safety-number symmetry, replacement and restart; username independence; legacy trust/lifecycle migration; destroyed-session rejection and authenticated reconnection; normal/prekey replay and delayed delivery; real database reopen; every outer-envelope binding and payload truncation/extension/bit flip; prekey namespaces, delayed delivery, retention and quota behavior; classified storage faults versus programmer errors; failed-commit plaintext wiping; and missing/corrupt/truncated wrapping data. Existing wrong-tag, missing-Keystore-key, database-loss and transaction rollback checks remain.

Debug and unsigned release builds passed. Lint reported zero errors and 22 dependency-age/starter-resource warnings, including three associated with concurrent launcher-resource changes outside this hardening commit. A normal strict-verification build/test run passed after bootstrapping checksums. A deliberately incorrect libsignal JAR checksum was rejected in a separate negative test; correct metadata was restored. Tests ran in the shared working tree, including the user's uncommitted launcher assets. The prior Phase 1A APK inspection excluded desktop and testing JNI resources; packaging rules are unchanged. These results do not validate other Android versions, real hardware, release signing or native binary provenance.

## Known limitations (not implemented)

TOFU does not authenticate a first contact. Candidate identity observations can cause denial of service; user verification must be independent of the untrusted directory. Routing-device identity is not a production username/contact-binding scheme. Event delivery is bounded; callers must read persisted status and handle IdentityChanged failures. If persisting a change itself fails, StorageFailure blocks the operation and no durable alert is claimed.

Operations assume one serialized engine per endpoint, without multiprocess coordination. The accepted-envelope ledger has a 10,000-record limit and stops accepting new messages at quota; it has no pruning policy. Issued prekey IDs are also bounded. The 30-day retention policy runs only on explicit maintenance, permits clock manipulation and can reject overdue first messages. No last-resort Kyber handling, publisher, scheduler or delivery outbox exists. A crash after commit but before result delivery can lose the application result. No exactly-once delivery guarantee is claimed.

CBOR uses one pinned encoder profile without cross-language conformance vectors or coverage-guided fuzzing. Source logging guards are not native-code audits. JVM/JNI/Room/SQLCipher copies and flash storage prevent deterministic erasure claims. Dependency checksums trust the bootstrapped baseline until independently attested.

## Cryptographic assumptions (assumed)

Trust libsignal's authenticated establishment, ratchets and message authentication; platform entropy; Android Keystore; SQLCipher; and the dependency supply chain. Public ratchet/counter progression tests do not prove forward secrecy or post-compromise security. Private identity keys authenticate establishment rather than directly encrypting application bodies. No custom cryptographic primitive or replacement handshake was added.

## Metadata currently visible

Mock/backend boundaries see ciphertext plus random sender/recipient IDs, envelope IDs, version, message type and packet length; public directory material is public when supplied. Local replay records retain accepted envelope identifiers. Future operators could observe network addresses, timing and sizes. Traffic padding and metadata anonymity are absent.

## Endpoint and storage risks

Unlocked endpoint/process compromise can recover plaintext and use database keys. Keystore keys are not user-authentication gated; software-backed protection is permitted and reported. Emulator checks are not hardware-backed validation. A database connection retains necessary password material until close. Native diagnostics, WAL/key lifetime, invalidation, backup behavior on real devices, screen capture and accessibility exposure need review.

## Rollback limitations

Restoring database N after N+50 can restore old ratchet, trust, lifecycle and replay state. SQLCipher authentication and Keystore wrapping do not detect an authentic old snapshot. The persistent replay ledger protects ordinary reopen, not adversarial rollback. No speculative anti-rollback mechanism was implemented. [Rollback review](protocol/ROLLBACK_RISK.md) distinguishes hardware key-deletion resistance from arbitrary database freshness and evaluates future counters, server anchors and append-only records.

## Server compromise consequences

The backend has no endpoint secrets or decryption API. Under the cryptographic assumptions, server material alone cannot decrypt captured messages. A compromised future server could deny service, replay, collect metadata, steal authentication credentials or substitute initial keys. Pinned replacement is rejected; first-contact substitution remains a risk. No recovery/admin decryption path exists.

## Dependencies requiring review

libsignal 0.102.1 remains unsupported for external use with unstable APIs and native/JNI dependencies. Its AGPL-3.0 obligations require owner/legal review before distribution; this is a release blocker, not a legal conclusion. SQLCipher 4.19.0 native provenance, logging, ABIs and notices need review. Room, serialization, coroutines and tooling also need supply-chain assessment. Exact coordinates, origins, source references, native artifacts and checksum procedures are in [dependency review](protocol/CRYPTO_DEPENDENCIES.md). No automatic upgrades or licensing-strategy change were made.

## Items requiring independent security audit

Trust transitions and contact binding; store callback/rollback semantics; prekey admission and lifecycle transitions; malicious bundle handling; crash/commit/cancellation boundaries; parsing/fuzzing; native logging and packaging; Keystore fallback/invalidation; SQLCipher and backup behavior; rollback detection; and supported Android versions on real hardware.

## Phase 1B blockers and future work

Owner/legal licensing review, independent security review, authenticated username/device binding and verification UX, production prekey allocation/publication/retirement contracts, rollback policy, transport authentication/TLS, metadata retention, quota policies and crash-safe delivery. Production networking/accounts/backend, Ghost Mode and all other later features remain unimplemented. Stop at Phase 1A.1.
