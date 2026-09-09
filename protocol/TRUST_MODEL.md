# Trust model — implemented in Phase 1A.1

Trust belongs to the random device ID and its pinned public identity key. A username is a local display label, never a trust anchor. `renameLocalUser("Robert")` changes no device ID, private identity or safety number. Another endpoint named Robert receives no trust from that name. A future username directory must prevent silent rebinding to another device ID; that service is not implemented.

## Persistent state and approval

| State | Meaning | Permitted transition |
|---|---|---|
| UNVERIFIED | First accepted key, using TOFU; no human authentication claimed | `verifyRemoteIdentity(id, expectedFingerprint)` → VERIFIED after independent comparison |
| VERIFIED | Caller explicitly confirmed the current safety number | Different observed key → CHANGED, high-priority event |
| CHANGED | Replacement observed; original pin retained and all session use blocked | `trustNewIdentity(id, expectedFingerprint)` approves the pending key → UNVERIFIED, then explicit reauthentication is required |

The caller must obtain the expected safety number from an independent authenticated comparison, not automatically copy it from the same untrusted directory. No QR scanner or polished verification screen is implemented. The API enables that future UI. Approval of a changed key is deliberately separate from claiming verification: the new pin is UNVERIFIED until `verifyRemoteIdentity` is called.

`getRemoteIdentityStatus` returns the state and its pre-change state. Both persist in SQLCipher. Existing Phase 1A pins lacking explicit state migrate to UNVERIFIED. Existing VERIFIED pins never downgrade on an unchanged key. `getRemoteFingerprint` continues returning the pinned relationship while CHANGED; `getPendingFingerprint` exposes the proposed relationship for review. An outdated or incorrect expected fingerprint fails without replacing the pin.

Libsignal callbacks cannot overwrite a pin. When a callback rejects a replacement, the crypto transaction rolls back first. A second atomic transaction persists CHANGED, the candidate public key, previous trust state and a requirement for reauthentication; it removes the old session. Only then is the live event emitted. Failure to commit that alert surfaces StorageFailure. A caller must stop on storage failure; inability to persist state cannot promise a durable alert. The notification channel is bounded; persistent status remains authoritative across missed events and restart. Previous VERIFIED state yields HIGH priority.

An observed candidate is untrusted input, not proof that the sender owns it. A malicious directory or forged packet can cause a fail-closed denial of service. Approval and a valid libsignal session establishment are still required; simply observing a candidate is never approval.

## Safety number review

Libsignal's `NumericFingerprintGenerator(5200).createFor(2, ...)` receives both device identifiers and both identity public keys. Its display value is symmetric for the two participants. The generator and its Rust implementation are the source of this behavior; Ghost Cloak does not invent a fingerprint algorithm. Tests cover matching values, stability after engine/SQLCipher reopen and a changed value after replacement. Only synthetic test identities may print safety numbers.

Sources reviewed at the pinned version: [Java fingerprint API](https://github.com/signalapp/libsignal/blob/v0.102.1/java/shared/java/org/signal/libsignal/protocol/fingerprint/NumericFingerprintGenerator.java), [Rust implementation](https://github.com/signalapp/libsignal/blob/v0.102.1/rust/protocol/src/fingerprint.rs).

## Device identifiers

`UUID.randomUUID()` uses a cryptographically strong pseudorandom generator. UUIDv4 has 122 random bits after its version/variant bits. Under independent uniform generation, the birthday approximation is n(n−1)/2^123; at one billion generated IDs the collision probability is about 9.4×10^-20. This is reasoning under the platform entropy assumption, not a randomness certification. No username, phone number, IMEI, MAC, serial or device model is an input. See the [Java UUID contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/UUID.html#randomUUID()).

## Not implemented

Account identity, username ownership, key transparency, production key distribution, trust synchronization, recovery and QR/UI verification. Database rollback can restore an older trust decision; see ROLLBACK_RISK.md. Independent review of the consent and migration boundaries remains required.
