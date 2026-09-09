# Phase 1B: identity, contacts and encrypted text

## Implemented experience

Ghost Cloak now has first-launch identity setup, Contacts, Add Contact, Conversation, Contact Security and Settings screens. Choose a 1–32 character username containing ASCII letters, digits or underscores. Existing Phase 1A identities open unchanged, even without prior UI metadata; absent UI state never triggers a reset. Settings uses renameLocalUser and preserves cryptographic identity and safety numbers.

The interface uses a charcoal/mint dark theme, readable typography, generous touch targets, text-plus-symbol trust indicators and a restrained vector emblem. System bars and launch background match the dark interface. Layouts scroll and respect system/keyboard insets, with a bounded content width for larger displays. No animation beyond standard Compose/navigation behavior is added.

## Source organization

| Location | Responsibility |
|---|---|
| Android/app/.../MainActivity.kt | Activity setup only |
| Android/app/.../application | Process-owned endpoint lifetime, view model, safe UI state and action orchestration |
| Android/app/.../ui/theme | Color.kt, Type.kt and Theme.kt: palette, typography and shapes |
| Android/app/.../ui/components | Reusable brand, avatar, trust badge, panels, buttons and headers |
| Android/app/.../ui/screens | One file per screen; message bubble presentation in a separate file |
| Android/app/.../ui/navigation | AndroidX Navigation Compose graph and shell |
| Android/app/.../ui/privacy | Explicit clipboard actions and sensitive-copy wrappers |
| Android/app/src/debug / src/release | Separate developer implementations; synthetic endpoints exist only in debug |
| Android/messaging | Contact cards, contact/message models, application validation, encrypted-record repository and ConversationService |
| Android/transport | Ciphertext-only LocalEncryptedRouter; no crypto or messaging-service dependency |

Themes contain no business logic. Screens receive domain/UI state and callbacks rather than an engine, database or transport. Application code instantiates the existing SignalProtocolEngine facade; libsignal vendor types remain confined to crypto and test internals. Crypto, identity, storage, framing, trust and session lifecycle implementations were not changed in Phase 1B.

## Contact cards and trust

A card is canonical CBOR encoded as strict Base64 with a GHOSTCLOAK:1: prefix. The maximum text size is 8,192 characters. Its schema carries version, public user/device UUIDs, username, public identity key, registration ID, EC/signed/Kyber public keys, their namespace IDs and signatures. No private keys, database secrets or session records are exported. Parsing rejects unsupported versions, unknown/duplicate/noncanonical fields, invalid identifiers and key sizes; the existing engine validates actual keys and signed bundle authentication. Deserialization executes no application code or Java serialization.

Import rejects self-contact, reused public-user IDs on different devices, different public-user IDs on an existing device and known public identity keys mapped to another contact device. Reimport of the same pinned card is rejected before modifying an active session. Duplicate display names are permitted because names are labels; their contact rows display a short device suffix for distinction. Public user IDs and labels are not authenticated directory/account identities. The safety number and remote device/key pin remain the trust anchor. No multi-device mapping is implied.

Generate a fresh card for each new contact exchange using the explicit button. An exported card is held in memory for copying and importing does not publish anything. Existing prekeys are retained according to Phase 1A.1 rules; a copied one-time bundle is not a reusable key directory. Repeated generation can hit the existing 32-retained-bundle limit and fails safely. No automatic prekey publisher or rotation service is added.

Imported contacts start UNVERIFIED. A matching fingerprint shown in the UI is not automatically VERIFIED. Mark verified opens a deliberate confirmation asking the user to compare every group through an independent channel. Trust status comes from SecureSessionEngine, not an editable contact-field copy. CHANGED blocks the composer and domain send operation. Previously verified replacements use a larger, bordered warning and preserve the prior trust status. A new identity requires an updated card plus explicit safety-number approval; acceptance leaves it UNVERIFIED and calls existing fingerprint-validated re-establishment. Failed validation does not silently retry or regain verification. The import path retains a candidate public card when the engine reports an identity change. A change observed on incoming traffic without a card requires importing the updated card before approval.

Local blocking preserves contacts and cryptographic pins and rejects delivery at the conversation boundary before decryption. It is distinct from session destruction. Session destruction controls and general reconnect/QR workflows are not exposed in this UI.

## Sending, receiving and persistence

ConversationService validates nonblank text, Unicode and the protocol's 16,384-byte UTF-8 body limit. The UI reports byte limits; it never silently truncates a message. English, Kreyòl, accented text, emoji and non-Latin text round-trip in tests. Invalid UTF-16 and oversized UTF-8 are rejected.

Sending records PENDING inside SQLCipher, calls SecureSessionEngine.encrypt, records ENCRYPTED, then sends only EncryptedEnvelope through EncryptedMessageTransport. Successful enqueue becomes SENT_TO_TRANSPORT. The router serializes/deserializes bounded envelopes and routes by opaque device ID. It has no decrypt method, plaintext payload API or dependency on crypto, storage or application services. Unknown destinations and full queues fail explicitly. No network permissions or services are added.

Receiving checks known-contact/block policy, calls SecureSessionEngine.decrypt, validates decoded text and persists the resulting local message. Only after that succeeds does the debug coordinator acknowledge DELIVERED_LOCAL_SIMULATION. That state is a local test observation, not a network/read receipt. Crypto and transport failures use safe categories without raw messages or causes.

Contact metadata, imported public-card mappings, pending replacement cards and message bodies are serialized under app/ record names in the existing endpoint_records SQLCipher table. There is no new plaintext database, preferences store, JSON cache, search index or notification content. The schema is unchanged. Conversations are keyed by remote device identity and reconstructed from contacts and message records on reopen. The current prototype caps contacts at 200 and messages at 5,000 per endpoint; quota exhaustion stops new writes. Drafts and displayed content exist in JVM/UI memory and drafts intentionally use remember rather than saved-state persistence.

SQLCipher protects at rest, not against an unlocked/compromised process. Crypto state commit and application-history commit are separate existing boundaries: a crash after crypto acceptance but before history persistence can lose a message while replay remains rejected. A crash after enqueue may leave a stale local delivery state. PENDING/ENCRYPTED records are not automatically retried or described as delivered. No outbox/exactly-once delivery guarantee or custom transaction bypass is added. This carries forward Phase 1A.1's documented crash-delivery limitation.

Local deletion removes only that endpoint's history row. It does not remove the peer copy, crypto replay ledger or guarantee physical erasure from memory, WAL or flash remnants.

## Run the local demonstration

Build/install debug, create or open your local identity, then choose Settings → Open local demo. Two separate SQLCipher/Keystore endpoints, demo-alice and demo-bob, exchange public cards. Alice sends the fixed synthetic greeting through the ciphertext router; Bob's service decrypts/persists it and sends a fixed simulated response through its own engine. Open Bob from Contacts, type more text, and inspect Contact Security. Settings → Return to my identity restores your original local endpoint. Demo histories survive process restart; re-enter the sandbox explicitly after reopening.

Only src/debug contains synthetic identities, greetings and the coordinator. The release implementation is disabled and contains no endpoint creation code. Imported cards in the normal app can establish crypto sessions, but another physical device is not reachable by the in-process router. The UI reports that limitation on attempted delivery. QR scanning, networking and production accounts are absent. No real plaintext is printed by the demo.

## Clipboard, screenshots and future app lock

Nothing is automatically copied. The explicit card Copy action and Compose text-field Copy/Cut paths set Android's sensitive clipboard flag. That flag helps suppress previews; it does not encrypt the clipboard or prevent access/sync by privileged software. Displayed history and safety numbers have no automatic copy behavior. Keyboard, accessibility, memory and screen-sharing exposure remain endpoint risks. See [Android clipboard guidance](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling).

FLAG_SECURE is not globally enabled. A future screen-capture setting needs a UX/accessibility review, task-preview treatment, clear user controls and testing on supported devices; it must not promise absolute screenshot prevention.

A future app lock could gate opening with BiometricPrompt/device credential. Merely hiding UI does not cryptographically lock an already-open database. Auth-bound Keystore keys would require an explicit key-wrapping migration, defined authentication validity/lock behavior, connection closure and memory-lifetime rules, and handling enrollment changes, lockouts, invalidation and device-credential fallback. Review those before binding database availability to biometric state. No biometric permission, lock gate or key migration is implemented here.

## Dependencies and review limits

Navigation Compose 2.9.7 and lifecycle-viewmodel-compose 2.9.4 are pinned in the version catalog from Google Maven. Crypto versions remain libsignal 0.102.1 and SQLCipher 4.19.0. Espresso alone moved from 3.5.1 to 3.7.0 because UI tests on API 37 failed on the removed reflective InputManager.getInstance API; the [official AndroidX Test release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0) document that fix. This is a test-only change. SHA-256 verification metadata includes newly resolved navigation/lifecycle/test artifacts. Strict verification passes; initial downloaded hashes still require independent provenance attestation before release.

Owner/legal AGPL review, independent security audit, endpoint/rollback risks, production contact binding, prekey policy, crash-safe delivery and real-device testing remain open. No production networking, backend deployment, accounts, notifications, media, Ghost Mode, analytics or Phase 1C work is included.
