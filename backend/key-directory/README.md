# Public directory boundary

The [Phase 1L contract](../../Android/ANONYMOUS_IDENTITY_DESIGN.md) uses an exact,
authenticated, rate-limited lookup by a server-generated 12-character Ghost Cloak
ID. The response contains public cryptographic material and no human name; one-time
prekey allocation remains atomic. The old username lookup is gone. The ID is public
pseudonymous metadata, not a verification or ownership credential. First-contact
key substitution still requires out-of-band verification.

**CURRENT source:** `MailboxService` implements authenticated exact-ID lookup
and a separate `/v2/directory/prekey` allocation endpoint. Lookup returns a
summary without consuming a one-time prekey. Allocation consumes one bundle
atomically and records a bounded idempotency response/tombstone in V008.
Production request limits use `PostgresRateLimiter`; the in-memory limiter is
for development/test fixtures. Public identity keys, signed public prekeys
and signed attachment capability advertisements may be returned; no private
key material is stored in the directory. See the [Q.2 contract and rollout](../../infrastructure/ABUSE_RESISTANCE_Q2.md).

First-contact key substitution remains possible without out-of-band
verification or key transparency. Older username and consuming-lookup
descriptions are **HISTORICAL**, not current endpoint instructions.
