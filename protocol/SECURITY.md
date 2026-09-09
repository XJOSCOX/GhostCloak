# Security architecture

UI calls domain APIs. Identity contains public domain types. Crypto owns the libsignal adapter. Storage supplies atomic endpoint-only secret records. Protocol defines bounded, versioned wire records. Transport and backend depend only on protocol/public types, never crypto or endpoint storage.

Private identity keys authenticate session establishment, never directly encrypt chat. Signal's library owns key agreement, ratcheting, message authentication and safety fingerprints. Android Keystore protects a randomly generated SQLCipher database secret. Its AES-GCM wrapping operation is local storage protection, not a messaging protocol.

Every crypto operation must commit all changes atomically before releasing ciphertext or plaintext. Authentication failure rolls back state. Serialize operations per endpoint; keep one engine per database. Never recreate identity silently when storage is corrupt or Keystore keys disappear.

Logging accepts fixed event codes only. Do not log messages, keys, tokens, passwords, envelopes, recovery secrets or database secrets. The developer harness may display fixed synthetic messages and opaque ciphertext in test output only.
