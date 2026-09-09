# Rollback risk — no mitigation implemented

## Concrete threat

An attacker with filesystem snapshot/restore capability saves database version N, lets the endpoint advance to N+50, then restores version N and its associated database/WAL/wrapped-secret files. If the same Keystore key is still available, both SQLCipher and AES-GCM can accept authentic but old state. Encryption authenticates bytes, not their freshness.

Restoration can reintroduce ratchet keys, skipped keys, consumed prekeys, trust decisions, session lifecycle permissions and accepted-envelope state. A packet rejected as a replay at N+50 may be accepted against N. Reuse of sending state may undermine protocol assumptions. Current exact-replay tests prove ordinary persistence across close/reopen, not resistance to restoring older files. The persistent replay ledger is inside the same rollback domain and does not solve this attack.

## Options for later review

| Option | Potential benefit | Limits and open questions |
|---|---|---|
| Hardware monotonic counter or protected sequence anchor | State outside filesystem rollback could detect an older database | No portable arbitrary app counter is assumed. Determine available OEM/TEE/StrongBox APIs, access, endurance, atomicity and failure handling on supported hardware. |
| Key deletion with hardware rollback resistance | May ensure deleted key blobs cannot be restored | KeyMint rollback resistance concerns deleted keys, not arbitrary SQLCipher revisions. Hardware capacity/support vary; key deletion/rotation would require a reviewed atomic crash/recovery design. It is not a counter API or a protection automatically provided by this wrapping key. |
| Server-assisted monotonic anchor | Independently retained revision could expose stale client state | Requires authentication, offline policy, crash handling and privacy review. The server is untrusted and can equivocate/roll back too; a single malicious service cannot be the sole unexamined freshness authority. No conversation keys should be uploaded. |
| Append-only authenticated history | Can expose rewriting within a retained history | A history stored only alongside the database can itself be rolled back. Needs an independently protected latest head or witness; it does not solve freshness alone. |
| Authenticated sequence anchors / external witnesses | Bind accepted history to an external latest revision | MACs/signatures alone do not distinguish an old valid anchor from a current one. Review trust, availability, counters, synchronization and fail-closed recovery before any design. |

Android Verified Boot/OS version binding protects a different rollback domain and must not be advertised as protection for application database contents. No speculative counter, hash chain or custom anti-rollback protocol is added here.

Primary research: [AOSP KeyMint rollback resistance](https://source.android.com/docs/security/features/keystore/implementer-ref#rollback-resistance) describes irreversible deletion of supported keys and finite protected storage; [AOSP Keystore features](https://source.android.com/docs/security/features/keystore/features) describes key authorization and root-of-trust binding. These are platform design references, not evidence that Ghost Cloak's database is rollback resistant.

## Release and later-phase requirement

Document the unsupported attack to reviewers and select a threat-appropriate policy before claiming replay/forward-secrecy guarantees against endpoint snapshot restoration. Test crash windows, malicious-server behavior, reset/reinstall and real hardware for any proposed mitigation. This remains an explicit security limitation and Phase 1B design blocker, not completed work.
