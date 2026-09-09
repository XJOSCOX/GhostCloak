# Phase 1A protocol profile

Use libsignal 0.102.1 SessionBuilder/SessionCipher, with signed EC and one-time Kyber prekeys. The library implements authenticated asynchronous session establishment and message ratchets. Application code never derives message keys. Bundle generation is endpoint-only. Public bundles cross the future directory boundary; private records never do.

One random UUID device routing ID is the Signal address name; the library device slot is fixed to 1 because multi-device is out of scope. The public device UUID is independent of random user UUID and local username. Registration IDs follow Signal's small positive integer range. No phone or hardware identity is used.

## Authentication and first contact

Prekey signatures bind session establishment to an identity key. The first accepted identity is pinned (TOFU), not a proof of the human's identity. Signal's numeric fingerprint uses both identity keys and stable device identifiers; users must compare it over an independent authenticated channel. Both endpoints compute the same code after a successful first message. Changed identities fail closed and emit RemoteIdentityChanged / IdentityChanged. No automatic acceptance or reset flow exists. Notification delivery is a bounded live stream; the returned IdentityChanged error remains authoritative if no collector is active. Pins persist across restarts and session destruction.

## Wire profile

Version 1 is Kotlin serialization CBOR with explicit fields in declaration order, defaults encoded and unknown fields rejected. The receiver requires byte-for-byte equality with the profile's re-encoding, rejecting trailing data and alternate representations. This is a versioned application framing profile, not a new cryptographic protocol. A future cross-language implementation must reproduce it with conformance vectors.

EncryptedEnvelope fields: protocolVersion=1, envelopeId=random UUID, senderDeviceId=UUID, recipientDeviceId=UUID, messageType=2 (Signal) or 3 (prekey), encryptedPayload=library bytes. Packet maximum is 131072 bytes; application body maximum is 16384 bytes. No timestamps, usernames or device details appear in routing headers.

The Signal-encrypted body contains version, envelope ID, sender, recipient and body bytes. The receiver validates these against the outer routing header before returning plaintext, inside the same transaction as ratchet changes. Thus routing edits cannot be accepted as another sender, recipient or envelope. Message type selects the library parser; unsupported types fail. Do not assume Signal authenticates every field of its outer prekey wrapper. Library authentication, binding checks and adversarial tests are all required.

Replay handling uses Signal's message counters and retained skipped-key/session state, never the outer UUID alone. Exact duplicate delivery is rejected. Bounded reordering is supported by the library; unlimited delays or gaps are not promised. Database rollback can roll back replay protection. Session destruction removes session records and retains a deny marker to prevent old prekey packets from recreating them; re-establishment after destruction is intentionally unavailable in this prototype.

## Boundaries

UI → domain interface → crypto → EncryptedEnvelope → transport/backend. Backend depends on protocol only and has no decryption API or endpoint store. The local mailbox parses framing but cannot authenticate ciphertext. Production networking and account authorization are absent. Future ServerMailboxTransport and GhostPeerTransport can implement the same transport interface; Ghost Mode is not implemented.

Crypto runs on Dispatchers.IO with a per-engine mutex and storage transaction. Use one engine/store per endpoint, no multiprocess access. Commit precedes returning ciphertext or plaintext. Cancellation never substitutes for a security failure. Storage failures propagate; no fallback store is created.
