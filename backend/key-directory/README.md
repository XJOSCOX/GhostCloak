# Public directory boundary

The [Phase 1L contract](../../Android/ANONYMOUS_IDENTITY_DESIGN.md) uses an exact,
authenticated, rate-limited lookup by a server-generated 12-character Ghost Cloak
ID. The response contains public cryptographic material and no human name; one-time
prekey allocation remains atomic. The old username lookup is gone. The ID is public
pseudonymous metadata, not a verification or ownership credential. First-contact
key substitution still requires out-of-band verification.

Future authenticated directory stores public identity keys and signed public prekey bundles only. No private material. One-time prekeys require atomic allocation. First-contact substitution remains possible without out-of-band verification/key transparency. This phase has no network endpoint; the harness exchanges public bundles directly.
