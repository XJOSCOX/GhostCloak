# Phase 1B security self-review

Developer self-review only; no independent security audit or production-readiness claim. Phase 0, 1A and 1A.1 remain the foundation. Phase 1C has not begun.

## Implemented

A separate messaging module and Compose application shell now provide local identity onboarding/rename, encrypted contacts, public contact-card exchange, explicit safety-number verification, blocked identity-change UI, one-to-one text history, local blocking and local deletion. Colors, typography, components, screens, navigation and application services are separated. The user-facing flow calls ConversationService, then the unchanged SecureSessionEngine. Neither UI nor router imports libsignal types.

Persistent trust is queried from the crypto engine. Contact-card import does not equate encryption with verification. Verification and replacement each require explicit confirmation with the displayed expected fingerprint. Changed identities disable both UI sending and domain sending; previously verified changes receive a stronger warning. Replacement approval requires an updated public card, preserves explicit re-establishment and leaves trust UNVERIFIED. Duplicate identity mappings are rejected; labels are not cryptographic anchors.

The local router accepts only encrypted envelopes. The debug coordinator uses two separate encrypted endpoints and acknowledges delivery only after the receiving service decrypts and persists the matching envelope. Release sources expose no synthetic endpoints. No INTERNET permission, network API, production backend, notifications, account signup or Ghost Mode was added.

Contacts, message bodies and local states are CBOR records inside the existing SQLCipher database. Database schema, Keystore wrapping and security core are unchanged. App-level UTF-8 validation rejects empty/whitespace text, malformed Unicode and oversized messages before encryption; messages are not silently truncated. Blocking preserves pins; deletion affects only local history. Message/card/model toString representations are redacted.

Copying is explicit and marked sensitive through both the public-card action and current/legacy Compose clipboard paths. The flag is not clipboard encryption. No payload logging or automatic clipboard copying is added. Drafts use memory-only Compose state. Screenshots, keyboard learning, accessibility exposure, native/JVM copies and task previews remain privacy considerations.

## Tests and validation

The test suite retains every Phase 1A/1A.1 security test. Phase 1B adds application tests for identity creation/rename, valid/invalid cards, duplicate/self mappings, UNVERIFIED import, persisted verification and messages, changed-identity sending rejection and explicit approval, byte limits, Unicode in both directions, blocking, deletion and local delivery correlation. Architectural checks cover router API/dependency boundaries and debug/release isolation.

Android instrumentation exercises SQLCipher close/reopen with identity, contacts, verified status and Unicode history. It checks that the database lacks a plaintext SQLite header and the synthetic message marker. Compose tests exercise onboarding, verification confirmation, changed-state disabled sending and sensitive clipboard flags. A complete app navigation test launches the isolated developer demo, sends text, verifies the contact and returns to the user's identity.

Final validation on 2026-09-09: all 55 tests passed (38 JVM test-support, 1 app unit, 7 app instrumentation, 9 storage instrumentation). Debug and unsigned release APK assembly passed. Lint reported zero errors and 23 dependency-age/starter-resource warnings, including concurrent launcher-asset warnings. The full README command passed with strict dependency verification. Existing libsignal/SQLCipher checksum entries are unchanged. Release DEX inspection found no demo endpoint names/greetings; the testing JNI library remains excluded.

All tests target the API 37 emulator rather than the attached physical phone. Visual inspection covers real emulator screens, dark system bars, keyboard layout and security controls. Testing on real hardware, API 30–36, larger fonts, assistive technologies and other form factors remains incomplete. Clipboard tests assert the sensitive flag before native handoff and native text delivery separately: emulator host synchronization was observed to replace returned metadata with its overlay-suppression flag. See LOGGING_AUDIT.md for evidence and the platform limitation.

## Assumed and not implemented

The same libsignal, entropy, Keystore and SQLCipher assumptions documented in SECURITY_REVIEW_PHASE_1A.md apply. No new crypto primitives, trust rules or replay mechanism were introduced. SQLCipher protects at rest; unlocked/process compromise can access message history and database keys. Native copies and flash remnants preclude deterministic erasure claims.

The Phase 1A.1 crash-delivery limitation remains: crypto commits and application-history writes are distinct. Process death between them may consume a valid message without preserving its body, or leave an ambiguous outbound delivery status. The UI does not infer delivery or automatically retransmit PENDING/ENCRYPTED rows. No durable outbox, network receipt or exactly-once guarantee exists. Endpoint operations remain serialized with a single process-owned engine/store per endpoint; no multiprocess support is added.

Authentic old database snapshots can roll back history, trust, lifecycle and replay state. Reopen tests do not demonstrate rollback resistance; protocol/ROLLBACK_RISK.md remains applicable. No custom freshness mechanism was added.

The mock router is in-process, bounded and volatile. Importing another device's card does not make that device reachable. Cards expose public identifiers and keys, carry untrusted display labels and require independent fingerprint comparison. They are not signed username ownership claims or reusable server directories. One-time prekeys must not be assumed reusable for arbitrary recipients. Fresh-card generation can exhaust the existing bounded prekey pool. Contacts/messages have explicit local capacity limits and no global search/index.

QR scanning, app lock, screenshot protection, recovery/backup, remote deletion, production transport/accounts and all later features remain absent. App lock and screenshot UX are future designs in protocol/PHASE_1B.md; neither changes key availability in this phase.

## Dependencies and release blockers

libsignal 0.102.1, SQLCipher 4.19.0 and cryptographic code remain unchanged. Unsupported external libsignal use, unstable APIs, native provenance and logging need independent review. AGPL obligations remain an owner/legal release blocker; repository privacy is not a licensing conclusion.

New AndroidX navigation/view-model dependencies and the Espresso API-37 test compatibility fix are pinned in the version catalog. Verification metadata records the newly resolved artifacts. Strict checksums enforce consistency against the checked-in baseline; independently attesting that baseline, native binaries and release signing remains required.

Release blockers include independent security review, owner/legal review, authenticated public-directory/contact mapping, production prekey policy, transport authentication, rollback/crash-delivery policy, metadata retention and real-device validation. Phase 1B is a local Android prototype, not a deployable messaging service.
