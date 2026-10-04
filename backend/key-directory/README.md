# Public directory boundary

The [Phase 1L.1 audit](../../Android/ANONYMOUS_IDENTITY_DESIGN.md) proposes exact
lookup by a random 12-character Ghost Cloak ID, no server-provided human name,
and a staged retirement of username lookup after old clients upgrade. The
current source still performs username lookup and atomically allocates a prekey.
The proposed ID is public pseudonymous metadata, not a verification or ownership
credential; first-contact key substitution still requires out-of-band verification.

Future authenticated directory stores public identity keys and signed public prekey bundles only. No private material. One-time prekeys require atomic allocation. First-contact substitution remains possible without out-of-band verification/key transparency. This phase has no network endpoint; the harness exchanges public bundles directly.
