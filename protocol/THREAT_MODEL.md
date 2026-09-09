# Threat model — Phase 0

Trust ends at the endpoint. The service, directory and network are untrusted. No master key, administrator decryption or server recovery secret is permitted.

| Adversary | Required defense and residual risk |
|---|---|
| Passive ISP, Wi-Fi operator or host | Only ciphertext leaves the crypto boundary. Future TLS protects transport independently of E2EE. IP addresses, timing, length and communication relationships remain observable. |
| Backend compromise, including source, database, environment, authentication secrets, TLS keys and logs | Endpoint private keys and ratchet state must never be uploaded. Server material alone must not decrypt historical conversations. Attackers can drop, delay, replay and substitute directory entries. |
| Malicious administrator | No message decryption API or escrow exists. An administrator can still deny service and substitute a first-contact key. |
| Database theft | Backend holds opaque ciphertext/public keys. Endpoint database requires a random secret wrapped by Android Keystore. An unlocked compromised endpoint can access it. |
| Tampering, injection, truncation, append, replay and reorder | Library authentication and replay state, strict framing, size limits and atomic endpoint transactions. Supported skipped-message state handles bounded reordering. |
| Identity substitution | Pin the first accepted key, reject changes and emit REMOTE_IDENTITY_CHANGED. First contact remains TOFU until safety numbers are verified independently. |
| Device compromise | Full access to an unlocked endpoint defeats message secrecy. E2EE does not protect plaintext while displayed or in memory. |

Security claims require correct libsignal integration, uncompromised random generation and endpoint code, authentic dependency artifacts, serialized state transitions and non-rollback storage. An attacker restoring an old endpoint database can undermine replay protection. Independent review is required.
