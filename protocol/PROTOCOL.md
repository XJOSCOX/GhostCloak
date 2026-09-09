# Phase 1A protocol profile

Use libsignal 0.102.1 SessionBuilder/SessionCipher, with signed EC and one-time Kyber prekeys. The library implements authenticated asynchronous session establishment and message ratchets. Application code never derives message keys. Bundle generation is endpoint-only. Public bundles cross the future directory boundary; private records never do.

One random UUID device routing ID is the Signal address name; the library device slot is fixed to 1 because multi-device is out of scope. The public device UUID is independent of random user UUID and local username. Registration IDs follow Signal's small positive integer range. No phone or hardware identity is used.

## Authentication and first contact

Prekey signatures bind session establishment to an identity key. First contact is explicitly UNVERIFIED (TOFU), not proof of a human identity. The domain verification API can mark the pinned key VERIFIED after independent safety-number comparison. Replacement records CHANGED durably, retains the old pin, invalidates the old session and emits a priority-aware event. Explicit candidate approval returns to UNVERIFIED and still requires reauthentication. Notification delivery is bounded; persistent status and returned errors are authoritative. See TRUST_MODEL.md for API semantics, expected-fingerprint requirements and migration behavior.

## Wire profile

Version 1 is Kotlin serialization CBOR with explicit fields in declaration order, defaults encoded and unknown fields rejected. The receiver requires byte-for-byte equality with the profile's re-encoding, rejecting trailing data and alternate representations. This is a versioned application framing profile, not a new cryptographic protocol. A future cross-language implementation must reproduce it with conformance vectors.

EncryptedEnvelope fields: protocolVersion=1, envelopeId=random UUID, senderDeviceId=UUID, recipientDeviceId=UUID, messageType=2 (Signal) or 3 (prekey), encryptedPayload=library bytes. Packet maximum is 131072 bytes; application body maximum is 16384 bytes. No timestamps, usernames or device details appear in routing headers.

The Signal-encrypted body contains version, envelope ID, sender, recipient and body bytes. The receiver validates these against the outer routing header before returning plaintext, inside the same transaction as ratchet changes. Thus routing edits cannot be accepted as another sender, recipient or envelope. Message type selects the library parser; unsupported types fail. Do not assume Signal authenticates every field of its outer prekey wrapper. Library authentication, binding checks and adversarial tests are all required.

Replay handling keeps Signal's counters and skipped-key/session state and additionally stores successfully authenticated sender/envelope-ID pairs in the same transaction. IDs are bound inside the encrypted body; an unaccepted attacker-supplied ID is never added. This ledger survives session deletion and prevents an accepted packet from being returned twice across ordinary restart or session replacement. It is capped at 10,000 entries per local endpoint; exhaustion fails closed instead of evicting replay state. No automatic expiration is implemented. Bounded reordering remains supported by libsignal. Restoring an old database can restore both replay mechanisms; see ROLLBACK_RISK.md.

## Explicit session lifecycle

ACTIVE permits the selected session's messages. DESTROYED rejects all incoming packets and ordinary establishment. REQUIRES_REAUTHENTICATION blocks ordinary establishment and accepts only a deliberately prepared fresh incoming handshake, if one exists. Legacy `destroyed/<device>` records migrate to DESTROYED.

For both endpoints to reconnect: the receiver calls `prepareReestablishment(peer, expectedFingerprint)`, deleting its old session and creating a new local publication bundle. It records that bundle's local signed/EC prekey IDs as the only admitted prekey pair for this peer. The initiator then calls `reestablishSession(bundle, expectedFingerprint)`, which validates the pin, creates a fresh libsignal session and allows only normal Signal replies on its incoming path. The initiator sends the first encrypted message. The receiver admits it only against the fresh IDs and releases plaintext/marks ACTIVE only after libsignal authentication and BoundContent checks commit. Wrong signatures or routing bindings roll back all session changes. ID checks are admission restrictions on the existing protocol, not a replacement for cryptographic authentication or a new cipher.

The receiving restriction stays associated with that session; later repeated prekey wrappers from its initial sender chain remain eligible, while old-key wrappers cannot restart it. The initiating endpoint rejects incoming prekey wrappers after explicit replacement. Simultaneous initiation without a designated receiver is not supported; callers must choose roles. If only one side was destroyed, an approved outbound session may target the still-active peer's new bundle. The two-sided preparation path is recommended whenever both sides need fresh-state assurance. Remote bundle age itself is not independently attested by this local prototype.

## Boundaries

UI → domain interface → crypto → EncryptedEnvelope → transport/backend. Backend depends on protocol only and has no decryption API or endpoint store. The local mailbox parses framing but cannot authenticate ciphertext. Production networking and account authorization are absent. Future ServerMailboxTransport and GhostPeerTransport can implement the same transport interface; Ghost Mode is not implemented.

Crypto runs on Dispatchers.IO with a per-engine mutex and storage transaction. Use one engine/store per endpoint, no multiprocess access. Commit precedes returning ciphertext or plaintext. Cancellation never substitutes for a security failure. Storage failures propagate; no fallback store is created.

## Failure classification and buffers

CryptoFailure exposes safe error and site enums, without upstream message/cause text. External frame parsing is scoped to MalformedFrame; there is no catch-all IllegalArgumentException around operations. Bad caller arguments remain programmer errors. Corrupt persisted libsignal records become StorageFailure/LOCAL_STATE, SQLite or failed commits become StorageFailure, and a library invariant failure becomes InternalProtocolFailure. Expected authentication, replay, wrong-recipient, missing-session, identity-change and reauthentication failures retain their distinct categories. Callback storage exceptions are runtime types because libsignal's checked-exception filter wraps unlisted checked exceptions in AssertionError. No broad Exception catch is used in production modules.

Owned encoded plaintext, decoded bodies and failed/cancelled return buffers are wiped where accessible. The caller owns a successfully returned plaintext buffer. Library/JVM/serializer copies are not deterministically erased. See LOGGING_AUDIT.md.
