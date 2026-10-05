# Security architecture

Current-source overview; historical Phase 1B details below describe the implementation at that stage. See [the audit register](../SECURITY_AUDIT_CURRENT.md) for present findings and [metadata privacy](METADATA_PRIVACY.md) for observer-specific limits.

UI calls domain APIs. Identity contains public domain types. Crypto owns the libsignal adapter. Storage supplies atomic endpoint-only secret records. Protocol defines bounded, versioned wire records. Transport and backend depend only on protocol/public types, never crypto or endpoint storage.

Private identity keys authenticate session establishment, never directly encrypt chat. Signal's library owns key agreement, ratcheting, message authentication and safety fingerprints. Android Keystore protects a randomly generated SQLCipher database secret. Its AES-GCM wrapping operation is local storage protection, not a messaging protocol.

Every crypto operation must commit all changes atomically before releasing ciphertext or plaintext. Authentication failure rolls back state. Serialize operations per endpoint; keep one engine per database. Never recreate identity silently when storage is corrupt or Keystore keys disappear.

Logging accepts fixed event codes only. Do not log messages, keys, tokens, passwords, envelopes, recovery secrets or database secrets. The developer harness may display fixed synthetic messages and opaque ciphertext in test output only.

ConversationService and LocalRepository sit above those boundaries. Contact metadata and local message history are serialized into SQLCipher endpoint records. Crypto commits and application history writes have distinct failure boundaries; no exactly-once network-delivery guarantee is implied. The backend has no endpoint decryption key. See [the current audit](../SECURITY_AUDIT_CURRENT.md) for App Lock, Safe Exit, request privacy, View Once and residual risks; [Phase 1B](PHASE_1B.md) is historical design context.
